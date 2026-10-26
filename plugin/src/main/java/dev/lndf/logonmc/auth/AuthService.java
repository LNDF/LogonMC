package dev.lndf.logonmc.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.lndf.logonmc.api.AuthReason;
import dev.lndf.logonmc.api.event.PlayerAuthenticatedEvent;
import dev.lndf.logonmc.api.event.PlayerLogoutEvent;
import dev.lndf.logonmc.api.event.PlayerRegisterEvent;
import dev.lndf.logonmc.api.event.PreAuthServerConnectEvent;
import dev.lndf.logonmc.LogonMCPlugin;
import dev.lndf.logonmc.config.Messages;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.crypto.HashedPassword;
import dev.lndf.logonmc.db.SessionRepository;
import dev.lndf.logonmc.db.StoredUser;
import dev.lndf.logonmc.db.UserRepository;
import dev.lndf.logonmc.mojang.MojangProfile;
import dev.lndf.logonmc.mojang.ProfileLookup;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;

import static dev.lndf.logonmc.config.Messages.arg;

/**
 * Per-player authentication: auto-login on join, password commands, sessions, and moving players
 * between the auth server and the rest of the network. Blocking work runs on the plugin executor.
 */
public final class AuthService {

    private final LogonMCPlugin plugin;
    private final ProxyServer proxy;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final ProfileLookup mojang;
    private final LoginResolver resolver;

    private static final int SHARED_IP_WARNING = 5;

    // Keyed by Player, not UUID: Velocity fires LoginEvent/DisconnectEvent for a rejected duplicate
    // login, which must not touch the online player's state.
    private final Map<Player, PlayerAuthState> states = new ConcurrentHashMap<>();
    // Accounts resolved during GameProfileRequestEvent, picked up by LoginEvent. Keyed by profile
    // UUID. Velocity's read timeout (30 s) closes connections that never reach LoginEvent, so older
    // entries are dead.
    private final Cache<UUID, Optional<StoredUser>> pendingProfiles = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).build();
    // Logins refused during GameProfileRequestEvent, by profile UUID, with the kick message key.
    private final Cache<UUID, String> refusedProfiles = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).build();
    private final LoginThrottle throttle;
    private final AtomicReference<Instant> lastSharedIpWarning = new AtomicReference<>(Instant.MIN);

    public AuthService(LogonMCPlugin plugin, UserRepository users, SessionRepository sessions, ProfileLookup mojang,
                       LoginResolver resolver) {
        this.plugin = plugin;
        this.proxy = plugin.proxy();
        this.users = users;
        this.sessions = sessions;
        this.mojang = mojang;
        this.resolver = resolver;
        this.throttle = new LoginThrottle(() -> config().auth().lockout());
    }

    private PluginConfig config() {
        return plugin.config();
    }

    private Messages messages() {
        return plugin.messages();
    }

    public @Nullable PlayerAuthState state(Player player) {
        return states.get(player);
    }

    public boolean isAuthenticated(Player player) {
        PlayerAuthState state = state(player);
        return state != null && state.isAuthenticated();
    }

    public void rememberProfile(UUID profileUuid, @Nullable StoredUser user) {
        pendingProfiles.put(profileUuid, Optional.ofNullable(user));
    }

    public void rememberProfileRefusal(UUID profileUuid, String messageKey) {
        refusedProfiles.put(profileUuid, messageKey);
    }

    /**
     * LoginEvent: creates the player's state and authenticates premium players and valid sessions.
     * Blocks on the database.
     *
     * @throws LoginResolver.LoginDeniedException when the profile phase refused this login
     */
    public void initialize(Player player) {
        String refusal = refusedProfiles.asMap().remove(player.getUniqueId());
        if (refusal != null) {
            throw new LoginResolver.LoginDeniedException(refusal, "Refused during the profile phase");
        }
        Optional<StoredUser> pending = pendingProfiles.asMap().remove(player.getUniqueId());
        StoredUser user = pending != null ? pending.orElse(null) : users.findByUuid(player.getUniqueId()).orElse(null);
        PlayerAuthState state = new PlayerAuthState(user);

        if (player.isOnlineMode() && user != null && user.isLinked()) {
            state.reason(AuthReason.PREMIUM);
        } else if (user != null && config().session().enabled()
                && sessions.isValid(user.uuid(), ip(player), Instant.now())) {
            state.reason(AuthReason.SESSION);
        }
        if (state.isAuthenticated() && user != null) {
            user.lastIp(ip(player));
            users.updateMetadata(user);
        }
        states.put(player, state);
    }

    /**
     * PostLoginEvent: announces auto-authenticated players to other plugins.
     */
    public void postLogin(Player player) {
        PlayerAuthState state = state(player);
        if (state != null && state.isAuthenticated()) {
            proxy.getEventManager().fireAndForget(new PlayerAuthenticatedEvent(player, state.reason()));
        }
        warnIfSharedIp(player);
    }

    private void warnIfSharedIp(Player player) {
        if (!config().session().enabled()) {
            return;
        }
        InetAddress address = address(player);
        long sharing = proxy.getAllPlayers().stream().filter(other -> address(other).equals(address)).count();
        Instant now = Instant.now();
        Instant last = lastSharedIpWarning.get();
        if (sharing >= SHARED_IP_WARNING && now.isAfter(last.plus(Duration.ofHours(1)))
                && lastSharedIpWarning.compareAndSet(last, now)) {
            plugin.logger().warn("{} players are connected from {}. Sessions let players on the same IP skip the "
                    + "password, so if Velocity is behind a proxy, enable PROXY protocol (haproxy-protocol in "
                    + "velocity.toml) or disable sessions in config.yml.", sharing, address.getHostAddress());
        }
    }

    /**
     * First ServerPostConnectEvent: the client can now display chat and titles, so greet
     * auto-authenticated players or start the login prompt.
     */
    private void firstServerJoined(Player player) {
        PlayerAuthState state = state(player);
        if (state == null) {
            return;
        }
        if (state.isAuthenticated()) {
            player.sendMessage(messages().get(state.reason() == AuthReason.PREMIUM ? "auth.premium" : "auth.session"));
            plugin.async(() -> suggestPremium(player, state));
        } else {
            startPrompt(player, state);
        }
    }

    /**
     * ServerPostConnectEvent: the first server starts the join flow; arriving back on the auth
     * server unauthenticated (after /logout) starts the login prompt.
     */
    public void serverPostConnect(Player player, boolean firstServer) {
        if (!isAuthenticated(player) && !isOnAuthServer(player)) {
            // Last resort behind GuardListener.
            plugin.logger().warn("{} reached {} without logging in; disconnecting", player.getUsername(),
                    player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("?"));
            player.disconnect(messages().get("kick.not-authenticated"));
            return;
        }
        if (firstServer) {
            firstServerJoined(player);
            return;
        }
        PlayerAuthState state = state(player);
        if (state != null && !state.isAuthenticated() && isOnAuthServer(player)) {
            startPrompt(player, state);
        }
    }

    public Optional<RegisteredServer> initialServer(Player player) {
        PlayerAuthState state = state(player);
        if (state != null && state.isAuthenticated()) {
            List<RegisteredServer> targets = targets(state);
            return targets.isEmpty() ? Optional.empty() : Optional.of(targets.getFirst());
        }
        return authServer();
    }

    public Optional<RegisteredServer> authServer() {
        return proxy.getServer(config().servers().auth());
    }

    public boolean isAuthServer(RegisteredServer server) {
        return server.getServerInfo().getName().equalsIgnoreCase(config().servers().auth());
    }

    public void serverConnected(Player player, RegisteredServer server) {
        PlayerAuthState state = state(player);
        if (state != null && state.isAuthenticated() && !isAuthServer(server)) {
            state.lastServer(server.getServerInfo().getName());
        }
    }

    public void disconnect(Player player) {
        PlayerAuthState state = states.remove(player);
        if (state == null) {
            return;
        }
        state.close();
        StoredUser user = state.user();
        if (state.isAuthenticated() && user != null) {
            user.lastSeen(Instant.now());
            user.lastServer(state.lastServer());
            plugin.async(() -> users.updateMetadata(user));
        }
    }

    private void startPrompt(Player player, PlayerAuthState state) {
        PluginConfig cfg = config();
        StoredUser user = state.user();
        boolean registered = user != null && user.hasPassword();
        Runnable remind = () -> {
            Messages msg = messages();
            player.sendMessage(msg.get(registered ? "prompt.login" : "prompt.register"));
        };
        Messages msg = messages();
        player.showTitle(Title.title(
                msg.get(registered ? "prompt.login-title" : "prompt.register-title"),
                msg.get(registered ? "prompt.subtitle-login" : "prompt.subtitle-register"),
                Title.Times.times(Duration.ofMillis(250), cfg.auth().loginTimeout(), Duration.ofMillis(250))));
        ScheduledTask reminder = proxy.getScheduler().buildTask(plugin, remind).repeat(cfg.auth().reminderInterval()).schedule();
        ScheduledTask timeout = proxy.getScheduler().buildTask(plugin, () -> {
            if (!state.isAuthenticated()) {
                player.disconnect(messages().get("kick.timeout"));
            }
        }).delay(cfg.auth().loginTimeout()).schedule();

        PluginConfig.BossBarSettings barSettings = cfg.auth().bossBar();
        if (!barSettings.enabled()) {
            state.startPrompt(null, reminder, timeout);
            return;
        }
        String barKey = registered ? "prompt.boss-bar-login" : "prompt.boss-bar-register";
        long timeoutMillis = Math.max(1, cfg.auth().loginTimeout().toMillis());
        long deadline = System.currentTimeMillis() + timeoutMillis;
        BossBar bar = BossBar.bossBar(bossBarName(barKey, timeoutMillis), 1f, barSettings.color(), barSettings.overlay());
        player.showBossBar(bar);
        // The client animates between updates, so once a second still drains smoothly.
        ScheduledTask drain = proxy.getScheduler().buildTask(plugin, () -> {
            long left = Math.max(0, deadline - System.currentTimeMillis());
            bar.progress((float) left / timeoutMillis);
            bar.name(bossBarName(barKey, left));
        }).delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
        state.startPrompt(() -> player.hideBossBar(bar), reminder, timeout, drain);
    }

    private Component bossBarName(String key, long millisLeft) {
        return messages().get(key, arg("seconds", (millisLeft + 999) / 1000));
    }

    public void login(Player player, String password) {
        PlayerAuthState state = state(player);
        if (state == null) {
            return;
        }
        if (state.isAuthenticated()) {
            player.sendMessage(messages().get("auth.already-authenticated"));
            return;
        }
        StoredUser user = state.user();
        if (user == null || !user.hasPassword()) {
            player.sendMessage(messages().get("auth.not-registered"));
            return;
        }
        passwordCheck(player, state, () -> {
            UUID account = user.uuid();
            InetAddress address = address(player);
            if (throttle.isLockedOut(account, address)) {
                player.disconnect(messages().get("kick.locked-out", lockoutMinutes()));
                return false;
            }
            HashedPassword hash = user.password();
            boolean correct;
            try {
                // Skipped if a lockout starts while queued for a hashing thread.
                correct = hash != null && plugin.hashers().verify(password, hash,
                        () -> state.isClosed() || throttle.isLockedOut(account, address));
            } catch (CancellationException e) {
                if (!state.isClosed()) {
                    player.disconnect(messages().get("kick.locked-out", lockoutMinutes()));
                }
                return false;
            }
            if (!correct) {
                throttle.recordFailure(account, address);
                int failures = state.recordFailure();
                int remaining = config().auth().maxAttempts() - failures;
                if (remaining <= 0) {
                    player.disconnect(messages().get("kick.too-many-attempts"));
                } else {
                    player.sendMessage(messages().get("auth.wrong-password", arg("remaining", remaining)));
                }
                return true;
            }
            // The account may have been reset or deleted, or locked out, during the verification.
            if (throttle.isLockedOut(account, address)) {
                player.disconnect(messages().get("kick.locked-out", lockoutMinutes()));
                return false;
            }
            Optional<StoredUser> current = currentAccount(user, hash);
            if (current.isEmpty()) {
                player.sendMessage(messages().get("auth.account-changed"));
                return false;
            }
            StoredUser fresh = current.get();
            throttle.recordSuccess(account);
            if (plugin.hashers().needsRehash(hash)) {
                HashedPassword upgraded = plugin.hashers().hash(password, state::isClosed);
                if (users.replacePassword(account, hash, upgraded)) {
                    fresh.password(upgraded);
                }
            }
            fresh.lastIp(ip(player));
            users.updateMetadata(fresh);
            state.user(fresh);
            player.sendMessage(messages().get("auth.logged-in"));
            authenticate(player, state, AuthReason.LOGIN);
            return false;
        });
    }

    public void register(Player player, String password, String confirm) {
        PlayerAuthState state = state(player);
        if (state == null) {
            return;
        }
        if (state.isAuthenticated()) {
            player.sendMessage(messages().get("auth.already-authenticated"));
            return;
        }
        if (state.user() != null && state.user().hasPassword()) {
            player.sendMessage(messages().get("auth.already-registered"));
            return;
        }
        if (!password.equals(confirm)) {
            player.sendMessage(messages().get("auth.passwords-dont-match"));
            return;
        }
        Component invalid = validatePassword(player, password);
        if (invalid != null) {
            player.sendMessage(invalid);
            return;
        }
        passwordCheck(player, state, () -> {
            // The join may have been let in as cracked because the Mojang lookup failed.
            String refusal = player.isOnlineMode() ? null : resolver.offlineRegistrationRefusal(player.getUsername());
            if (refusal != null) {
                player.sendMessage(messages().get(refusal));
                return false;
            }
            PlayerRegisterEvent event = proxy.getEventManager().fire(new PlayerRegisterEvent(player)).join();
            if (!event.getResult().isAllowed()) {
                event.getResult().getReasonComponent().ifPresent(player::sendMessage);
                return false;
            }
            StoredUser user = state.user();
            boolean exists = user != null;
            if (!exists) {
                user = StoredUser.create(player.getUniqueId(), player.getUsername());
            }
            user.password(plugin.hashers().hash(password, state::isClosed));
            user.lastIp(ip(player));
            if (exists) {
                if (!users.replacePassword(user.uuid(), null, user.password())) {
                    player.sendMessage(messages().get("auth.account-changed"));
                    return false;
                }
                users.updateMetadata(user);
            } else {
                users.insert(user);
            }
            state.user(user);
            player.sendMessage(messages().get("auth.registered"));
            authenticate(player, state, AuthReason.REGISTER);
            return false;
        });
    }

    public void changePassword(Player player, @Nullable String oldPassword, String newPassword) {
        PlayerAuthState state = state(player);
        if (state == null || !state.isAuthenticated() || state.user() == null) {
            player.sendMessage(messages().get("auth.not-authenticated"));
            return;
        }
        Component invalid = validatePassword(player, newPassword);
        if (invalid != null) {
            player.sendMessage(invalid);
            return;
        }
        StoredUser user = state.user();
        passwordCheck(player, state, () -> {
            HashedPassword current = user.password();
            if (current != null && !confirmPassword(player, state, user, oldPassword)) {
                return true;
            }
            HashedPassword replacement = plugin.hashers().hash(newPassword, state::isClosed);
            if (!users.replacePassword(user.uuid(), current, replacement)) {
                player.sendMessage(messages().get("auth.account-changed"));
                return false;
            }
            user.password(replacement);
            sessions.delete(user.uuid());
            player.sendMessage(messages().get("auth.password-changed"));
            return false;
        });
    }

    public void logout(Player player) {
        PlayerAuthState state = state(player);
        if (state == null || !state.isAuthenticated()) {
            player.sendMessage(messages().get("auth.not-authenticated"));
            return;
        }
        plugin.async(() -> {
            StoredUser user = state.user();
            if (user != null) {
                sessions.delete(user.uuid());
            }
            // Before the switch to the auth server, which could swallow it.
            player.sendMessage(messages().get("auth.logged-out"));
            deauthenticate(player, state);
        });
    }

    /**
     * /premium: after the password is confirmed, the next join must pass Mojang authentication,
     * which proves ownership of the premium account before it is linked.
     */
    public void requestPremium(Player player, String password) {
        PlayerAuthState state = state(player);
        StoredUser user = state == null ? null : state.user();
        if (state == null || !state.isAuthenticated() || user == null) {
            player.sendMessage(messages().get("auth.not-authenticated"));
            return;
        }
        if (user.isLinked()) {
            player.sendMessage(messages().get("premium.already-premium"));
            return;
        }
        passwordCheck(player, state, () -> {
            if (!confirmPassword(player, state, user, password)) {
                return true;
            }
            Optional<MojangProfile> profile;
            try {
                profile = mojang.lookup(player.getUsername());
            } catch (ProfileLookup.LookupException e) {
                player.sendMessage(messages().get("auth.error"));
                return false;
            }
            if (profile.isEmpty() || !profile.get().name().equals(player.getUsername())) {
                player.sendMessage(messages().get("premium.not-premium-name", arg("name", player.getUsername())));
                return false;
            }
            if (users.findByPremiumUuid(profile.get().id()).isPresent()) {
                player.sendMessage(messages().get("premium.already-linked", arg("name", player.getUsername())));
                return false;
            }
            resolver.requestPremiumCheck(player.getUsername());
            sessions.delete(user.uuid());
            player.disconnect(messages().get("kick.premium-rejoin"));
            return false;
        });
    }

    public void becomeCracked(Player player) {
        PlayerAuthState state = state(player);
        StoredUser user = state == null ? null : state.user();
        if (state == null || !state.isAuthenticated() || user == null) {
            player.sendMessage(messages().get("auth.not-authenticated"));
            return;
        }
        if (!user.isLinked()) {
            player.sendMessage(messages().get("premium.not-premium"));
            return;
        }
        plugin.async(() -> {
            // A cracked account owns its name again, so it must still be free.
            Optional<StoredUser> owner = users.findCrackedByName(user.lastNickname());
            if (owner.isPresent() && !owner.get().uuid().equals(user.uuid())) {
                player.sendMessage(messages().get("premium.name-taken", arg("name", user.lastNickname())));
                return;
            }
            user.premiumUuid(null);
            users.updateLink(user);
            player.disconnect(messages().get("kick.cracked-rejoin"));
        });
    }

    public void unregister(Player player, String password) {
        PlayerAuthState state = state(player);
        StoredUser user = state == null ? null : state.user();
        if (state == null || !state.isAuthenticated() || user == null) {
            player.sendMessage(messages().get("auth.not-authenticated"));
            return;
        }
        passwordCheck(player, state, () -> {
            if (user.hasPassword() && !confirmPassword(player, state, user, password)) {
                return true;
            }
            users.delete(user.uuid());
            player.disconnect(messages().get("kick.unregistered"));
            return false;
        });
    }

    /**
     * Authenticates without a password. Blocks on events; call from the executor.
     */
    public boolean forceLogin(Player player) {
        PlayerAuthState state = state(player);
        if (state == null || state.isAuthenticated()) {
            return false;
        }
        player.sendMessage(messages().get("auth.forced"));
        authenticate(player, state, AuthReason.API);
        return true;
    }

    public void forceLogout(Player player) {
        PlayerAuthState state = state(player);
        if (state == null || !state.isAuthenticated()) {
            return;
        }
        StoredUser user = state.user();
        if (user != null) {
            sessions.delete(user.uuid());
        }
        deauthenticate(player, state);
    }

    /**
     * Refreshes the cached account of an online player after it was changed elsewhere.
     */
    public void refreshUser(UUID uuid, @Nullable StoredUser user) {
        proxy.getPlayer(uuid).map(states::get).ifPresent(state -> state.user(user));
    }

    private void authenticate(Player player, PlayerAuthState state, AuthReason reason) {
        if (state.isClosed()) {
            return;
        }
        state.reason(reason);
        state.cancelTasks();
        player.clearTitle();
        StoredUser user = state.user();
        if (user != null && (reason == AuthReason.LOGIN || reason == AuthReason.REGISTER) && config().session().enabled()) {
            sessions.save(user.uuid(), ip(player), Instant.now().plus(config().session().duration()));
        }
        proxy.getEventManager().fire(new PlayerAuthenticatedEvent(player, reason)).join();
        sendOn(player, state);
        // Delayed so the message isn't lost while the player switches away from the auth server.
        proxy.getScheduler().buildTask(plugin, () -> plugin.async(() -> suggestPremium(player, state)))
                .delay(3, TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * Tells a cracked account whose name belongs to an unlinked Mojang account about /premium, once
     * per connection. Blocks on the Mojang lookup.
     */
    private void suggestPremium(Player player, PlayerAuthState state) {
        StoredUser user = state.user();
        if (!config().premium().suggestPremium() || !player.isActive() || !state.isAuthenticated()
                || user == null || user.isLinked() || !user.hasPassword()) {
            return;
        }
        if (state.markPremiumSuggested() && resolver.isLinkablePremiumName(player.getUsername())) {
            player.sendMessage(messages().get("premium.suggest"));
        }
    }

    private void deauthenticate(Player player, PlayerAuthState state) {
        state.reason(null);
        proxy.getEventManager().fire(new PlayerLogoutEvent(player)).join();
        Optional<RegisteredServer> auth = authServer();
        if (auth.isEmpty()) {
            player.disconnect(messages().get("kick.no-auth-server"));
            return;
        }
        if (isOnAuthServer(player)) {
            startPrompt(player, state);
            return;
        }
        // The prompt starts in serverPostConnect once the player is there: a title sent during the
        // switch is cleared by the client. A player who can't reach the auth server isn't left
        // unauthenticated on a backend server.
        player.createConnectionRequest(auth.get()).connect().whenComplete((result, error) -> {
            if (player.isActive() && (error != null || result == null || !result.isSuccessful())) {
                player.disconnect(messages().get("kick.no-auth-server"));
            }
        });
    }

    private boolean isOnAuthServer(Player player) {
        return player.getCurrentServer().map(connection -> isAuthServer(connection.getServer())).orElse(false);
    }

    /**
     * Moves a freshly authenticated player from the auth server to their last server or a lobby,
     * trying the next candidate when a connection fails.
     */
    private void sendOn(Player player, PlayerAuthState state) {
        boolean onAuthServer = player.getCurrentServer().map(c -> isAuthServer(c.getServer())).orElse(true);
        if (!onAuthServer) {
            return;
        }
        List<RegisteredServer> targets = targets(state);
        PreAuthServerConnectEvent event = proxy.getEventManager()
                .fire(new PreAuthServerConnectEvent(player, targets.isEmpty() ? null : targets.getFirst())).join();
        RegisteredServer chosen = event.getTarget();
        if (chosen == null) {
            return;
        }
        List<RegisteredServer> queue = new ArrayList<>();
        queue.add(chosen);
        targets.stream().filter(s -> !s.equals(chosen)).forEach(queue::add);
        connect(player, queue.iterator());
    }

    private void connect(Player player, Iterator<RegisteredServer> candidates) {
        if (!candidates.hasNext()) {
            player.sendMessage(messages().get("auth.connect-failed"));
            return;
        }
        RegisteredServer server = candidates.next();
        player.createConnectionRequest(server).connect().whenComplete((result, error) -> {
            if (!player.isActive()) {
                return;
            }
            if (error != null || result == null || !result.isSuccessful()) {
                connect(player, candidates);
            }
        });
    }

    private List<RegisteredServer> targets(PlayerAuthState state) {
        PluginConfig.Servers servers = config().servers();
        List<RegisteredServer> targets = new ArrayList<>();
        String last = state.lastServer();
        if (servers.rememberLastServer() && last != null && !last.equalsIgnoreCase(servers.auth())) {
            proxy.getServer(last).ifPresent(targets::add);
        }
        for (String name : servers.lobby()) {
            proxy.getServer(name).filter(s -> !targets.contains(s)).ifPresent(targets::add);
        }
        return targets;
    }

    private @Nullable Component validatePassword(Player player, String password) {
        PluginConfig.Auth auth = config().auth();
        if (password.length() < auth.passwordMinLength()) {
            return messages().get("auth.password-too-short", arg("min", auth.passwordMinLength()));
        }
        if (password.length() > auth.passwordMaxLength()) {
            return messages().get("auth.password-too-long", arg("max", auth.passwordMaxLength()));
        }
        if (password.toLowerCase(Locale.ROOT).equals(player.getUsername().toLowerCase(Locale.ROOT))) {
            return messages().get("auth.password-is-name");
        }
        if (plugin.unsafePasswordsUnavailable()) {
            return messages().get("auth.password-check-unavailable");
        }
        if (plugin.isUnsafePassword(password)) {
            return messages().get("auth.password-unsafe");
        }
        return null;
    }

    /** A password command's work; returns true when the player gave a wrong password. */
    @FunctionalInterface
    private interface PasswordTask {
        boolean run();
    }

    private void passwordCheck(Player player, PlayerAuthState state, PasswordTask task) {
        if (!state.beginPasswordCheck()) {
            player.sendMessage(messages().get("auth.wait"));
            return;
        }
        plugin.async(() -> {
            boolean wrong = false;
            try {
                if (!state.isClosed()) {
                    wrong = task.run();
                }
            } catch (RejectedExecutionException e) {
                player.sendMessage(messages().get("auth.busy"));
            } catch (CancellationException e) {
                // The hash was skipped because the player left.
            } catch (RuntimeException e) {
                plugin.logger().error("Authentication action failed for {}", player.getUsername(), e);
                player.sendMessage(messages().get("auth.error"));
            } finally {
                Duration delay = config().auth().wrongPasswordDelay();
                if (wrong && delay.isPositive()) {
                    proxy.getScheduler().buildTask(plugin, state::endPasswordCheck).delay(delay).schedule();
                } else {
                    state.endPasswordCheck();
                }
            }
        });
    }

    // Counts towards the lockout, or these commands could be used to guess the password.
    private boolean confirmPassword(Player player, PlayerAuthState state, StoredUser user, @Nullable String password) {
        UUID account = user.uuid();
        InetAddress address = address(player);
        if (throttle.isLockedOut(account, address)) {
            player.sendMessage(messages().get("auth.locked-out", lockoutMinutes()));
            return false;
        }
        HashedPassword hash = user.password();
        boolean correct;
        try {
            correct = hash != null && password != null && plugin.hashers().verify(password, hash,
                    () -> state.isClosed() || throttle.isLockedOut(account, address));
        } catch (CancellationException e) {
            if (!state.isClosed()) {
                player.sendMessage(messages().get("auth.locked-out", lockoutMinutes()));
            }
            return false;
        }
        if (!correct) {
            throttle.recordFailure(account, address);
            player.sendMessage(messages().get("auth.wrong-password", arg("remaining", "-")));
            return false;
        }
        if (throttle.isLockedOut(account, address)) {
            player.sendMessage(messages().get("auth.locked-out", lockoutMinutes()));
            return false;
        }
        if (currentAccount(user, hash).isEmpty()) {
            player.sendMessage(messages().get("auth.account-changed"));
            return false;
        }
        return true;
    }

    /** Empty if the account was deleted or its password changed, possibly by another proxy. */
    private Optional<StoredUser> currentAccount(StoredUser user, HashedPassword verified) {
        return users.findByUuid(user.uuid())
                .filter(current -> current.password() != null && current.password().hash().equals(verified.hash()));
    }

    private TagResolver lockoutMinutes() {
        return arg("minutes", config().auth().lockout().duration().toMinutes());
    }

    public void purgeSessionsLater() {
        proxy.getScheduler().buildTask(plugin, () -> plugin.async(() -> sessions.purgeExpired(Instant.now())))
                .delay(1, TimeUnit.MINUTES)
                .repeat(1, TimeUnit.HOURS)
                .schedule();
    }

    public static String ip(Player player) {
        return address(player).getHostAddress();
    }

    private static InetAddress address(Player player) {
        return player.getRemoteAddress().getAddress();
    }
}
