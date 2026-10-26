package dev.lndf.logonmc;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.lndf.logonmc.api.LogonMCProvider;
import dev.lndf.logonmc.auth.AuthService;
import dev.lndf.logonmc.auth.LoginResolver;
import dev.lndf.logonmc.command.AdminCommand;
import dev.lndf.logonmc.command.AuthCommands;
import dev.lndf.logonmc.config.ConfigLoader;
import dev.lndf.logonmc.config.Messages;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.crypto.Hashers;
import dev.lndf.logonmc.crypto.UnsafePasswords;
import dev.lndf.logonmc.db.Database;
import dev.lndf.logonmc.db.SchemaMigrator;
import dev.lndf.logonmc.db.SessionRepository;
import dev.lndf.logonmc.db.UserRepository;
import dev.lndf.logonmc.listener.ConnectionListener;
import dev.lndf.logonmc.listener.GuardListener;
import dev.lndf.logonmc.mojang.MojangClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(
        id = "logonmc",
        name = "LogonMC",
        version = BuildConstants.VERSION,
        description = "Login plugin with premium autologin, sessions and limbo routing",
        authors = {"LNDF"}
)
public final class LogonMCPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ConfigLoader configLoader;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // Unbounded on purpose: executeHash enforces auth.hash-queue-size, which a reload can change.
    private final ThreadPoolExecutor hashExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().name("LogonMC-hash-", 0).daemon().factory());

    private volatile PluginConfig config;
    private volatile Messages messages;
    private volatile Hashers hashers;
    private final UnsafePasswords unsafePasswords = new UnsafePasswords();
    private Database database;

    @Inject
    public LogonMCPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configLoader = new ConfigLoader(dataDirectory);
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            loadConfiguration();
            database = Database.create(config.database(), dataDirectory);
            int applied = new SchemaMigrator(database).migrate();
            if (applied > 0) {
                logger.info("Applied {} database migration(s)", applied);
            }
        } catch (Exception e) {
            logger.error("LogonMC failed to start. All logins are refused until this is fixed.", e);
            proxy.getEventManager().register(this, PreLoginEvent.class, Short.MAX_VALUE, pre ->
                    pre.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                            Component.text("The login system is unavailable.", NamedTextColor.RED))));
            return;
        }

        UserRepository users = new UserRepository(database);
        SessionRepository sessions = new SessionRepository(database);
        MojangClient mojang = new MojangClient(config.premium().mojangCache(), () -> config().premium().lookupSources());
        LoginResolver resolver = new LoginResolver(users, mojang, this::config,
                e -> proxy.getEventManager().fire(e), logger);
        AuthService auth = new AuthService(this, users, sessions, mojang, resolver);
        // Before commands exist, so no password is ever checked against an empty list.
        readUnsafePasswords();

        proxy.getEventManager().register(this, new ConnectionListener(this, resolver, auth));
        proxy.getEventManager().register(this, new GuardListener(this, auth));
        new AuthCommands(this, auth).register(proxy.getCommandManager());
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("logonmc").plugin(this).build(),
                new AdminCommand(this, auth, resolver, users, sessions, mojang));
        auth.purgeSessionsLater();

        LogonMCProvider.register(new LogonMCImpl(this, auth, users, sessions));
        logger.info("LogonMC {} enabled ({} database, auth server '{}')",
                BuildConstants.VERSION, config.database().type(), config.servers().auth());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        LogonMCProvider.register(null);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
            hashExecutor.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (database != null) {
            database.close();
        }
    }

    private void loadConfiguration() throws Exception {
        PluginConfig loaded = configLoader.loadConfig();
        Messages loadedMessages = configLoader.loadMessages();
        this.config = loaded;
        this.messages = loadedMessages;
        resizeHashExecutor(loaded.auth().hashThreads());
        this.hashers = new Hashers(loaded.auth().hashAlgorithm(), this::executeHash);
    }

    private void executeHash(Runnable task) {
        if (hashExecutor.getQueue().size() >= config.auth().hashQueueSize()) {
            throw new RejectedExecutionException("Password hashing queue is full");
        }
        hashExecutor.execute(task);
    }

    private void resizeHashExecutor(int threads) {
        // The core size may never exceed the maximum, so the order depends on the direction.
        if (threads > hashExecutor.getMaximumPoolSize()) {
            hashExecutor.setMaximumPoolSize(threads);
            hashExecutor.setCorePoolSize(threads);
        } else {
            hashExecutor.setCorePoolSize(threads);
            hashExecutor.setMaximumPoolSize(threads);
        }
    }

    /**
     * Reads the unsafe-password lists in the background; the previous lists stay in use meanwhile.
     */
    private void loadUnsafePasswords() {
        async(this::readUnsafePasswords);
    }

    private void readUnsafePasswords() {
        PluginConfig.UnsafePasswords settings = config.auth().unsafePasswords();
        if (!settings.enabled()) {
            return;
        }
        try {
            long start = System.nanoTime();
            int count = unsafePasswords.load(configLoader.unsafeListsDirectory(), settings.caseSensitive());
            logger.info("Loaded {} unsafe passwords in {} ms", count, (System.nanoTime() - start) / 1_000_000);
        } catch (IOException e) {
            logger.error(unsafePasswords.isLoaded()
                    ? "Could not read the unsafe password lists; keeping the previous ones"
                    : "Could not read the unsafe password lists; new passwords are refused until /logonmc reload loads them", e);
        }
    }

    /**
     * Reloads config.yml, messages.yml and the unsafe-password lists. Database settings only apply
     * after a restart.
     */
    public boolean reload() {
        try {
            loadConfiguration();
            loadUnsafePasswords();
            return true;
        } catch (Exception e) {
            logger.error("Could not reload the configuration; keeping the previous one", e);
            return false;
        }
    }

    public void async(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.error("Unhandled error in LogonMC task", t);
            }
        });
    }

    public ExecutorService executor() {
        return executor;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public Hashers hashers() {
        return hashers;
    }

    public boolean unsafePasswordsUnavailable() {
        return config.auth().unsafePasswords().enabled() && !unsafePasswords.isLoaded();
    }

    public boolean isUnsafePassword(String password) {
        return config.auth().unsafePasswords().enabled() && unsafePasswords.contains(password);
    }
}
