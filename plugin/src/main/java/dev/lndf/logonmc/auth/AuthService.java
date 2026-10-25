package dev.lndf.logonmc.auth;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
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

    private final Map<UUID, PlayerAuthState> states = new ConcurrentHashMap<>();
    // Accounts resolved during GameProfileRequestEvent, picked up by LoginEvent. Keyed by profile UUID.
    private final Map<UUID, Optional<StoredUser>> pendingProfiles = new ConcurrentHashMap<>();
    // Logins refused during GameProfileRequestEvent, by profile UUID, with the kick message key.
    private final Map<UUID, String> refusedProfiles = new ConcurrentHashMap<>();

    public AuthService(LogonMCPlugin plugin, UserRepository users, SessionRepository sessions, ProfileLookup mojang,
                       LoginResolver resolver) {
        this.plugin = plugin;
        this.proxy = plugin.proxy();
        this.users = users;
        this.sessions = sessions;
        this.mojang = mojang;
        this.resolver = resolver;
    }

    private PluginConfig config() {
        return plugin.config();
    }

    private Messages messages() {
        return plugin.messages();
    }

    public @Nullable PlayerAuthState state(Player player) {
        return states.get(player.getUniqueId());
    }

    public boolean isAuthenticated(Player player) {
        PlayerAuthState state = state(player);
        return state != null && state.isAuthenticated();
    }

    // ---- Join lifecycle ----

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
        String refusal = refusedProfiles.remove(player.getUniqueId());
        if (refusal != null) {
            throw new LoginResolver.LoginDeniedException(refusal, "Refused during the profile phase");
        }
        Optional<StoredUser> pending = pendingProfiles.remove(player.getUniqueId());
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
            users.update(user);
        }
        states.put(player.getUniqueId(), state);
    }

    /**
     * PostLoginEvent: announces auto-authenticated players to other plugins.
     */
    public void postLogin(Player player) {
        PlayerAuthState state = state(player);
        if (state != null && state.isAuthenticated()) {
            proxy.getEventManager().fireAndForget(new PlayerAuthenticatedEvent(player, state.reason()));
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
        PlayerAuthState state = states.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.cancelTasks();
        StoredUser user = state.user();
        if (state.isAuthenticated() && user != null) {
            user.lastSeen(Instant.now());
            user.lastServer(state.lastServer());
            plugin.async(() -> users.update(user));
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
        state.scheduleTasks(
                proxy.getScheduler().buildTask(plugin, remind).repeat(cfg.auth().reminderInterval()).schedule(),
                proxy.getScheduler().buildTask(plugin, () -> {
                    if (!state.isAuthenticated()) {
                        player.disconnect(messages().get("kick.timeout"));
                    }
                }).delay(cfg.auth().loginTimeout()).schedule()
        );
    }

    // ---- Password commands ----

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
        plugin.async(() -> {
            HashedPassword hash = user.password();
            if (hash == null || !plugin.hashers().verify(password, hash)) {
                int failures = state.recordFailure();
                int remaining = config().auth().maxAttempts() - failures;
                if (remaining <= 0) {
                    player.disconnect(messages().get("kick.too-many-attempts"));
                } else {
                    player.sendMessage(messages().get("auth.wrong-password", arg("remaining", remaining)));
                }
                return;
            }
            if (plugin.hashers().needsRehash(hash)) {
                user.password(plugin.hashers().hash(password));
            }
            user.lastIp(ip(player));
            users.update(user);
            player.sendMessage(messages().get("auth.logged-in"));
            authenticate(player, state, AuthReason.LOGIN);
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
        proxy.getEventManager().fire(new PlayerRegisterEvent(player)).thenAcceptAsync(event -> {
            if (!event.getResult().isAllowed()) {
                event.getResult().getReasonComponent().ifPresent(player::sendMessage);
                return;
            }
            StoredUser user = state.user();
            boolean exists = user != null;
            if (!exists) {
                user = StoredUser.create(player.getUniqueId(), player.getUsername());
            }
            user.password(plugin.hashers().hash(password));
            user.lastIp(ip(player));
            if (exists) {
                users.update(user);
            } else {
                users.insert(user);
            }
            state.user(user);
            player.sendMessage(messages().get("auth.registered"));
            authenticate(player, state, AuthReason.REGISTER);
        }, plugin.executor()).exceptionally(error -> fail(player, error));
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
        plugin.async(() -> {
            HashedPassword current = user.password();
            if (current != null && (oldPassword == null || !plugin.hashers().verify(oldPassword, current))) {
                player.sendMessage(messages().get("auth.wrong-password", arg("remaining", "-")));
                return;
            }
            user.password(plugin.hashers().hash(newPassword));
            users.update(user);
            sessions.delete(user.uuid());
            player.sendMessage(messages().get("auth.password-changed"));
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
        player.sendMessage(messages().get("premium.checking"));
        plugin.async(() -> {
            HashedPassword hash = user.password();
            if (hash == null || !plugin.hashers().verify(password, hash)) {
                player.sendMessage(messages().get("auth.wrong-password", arg("remaining", "-")));
                return;
            }
            Optional<MojangProfile> profile;
            try {
                profile = mojang.lookup(player.getUsername());
            } catch (ProfileLookup.LookupException e) {
                player.sendMessage(messages().get("auth.error"));
                return;
            }
            if (profile.isEmpty() || !profile.get().name().equals(player.getUsername())) {
                player.sendMessage(messages().get("premium.not-premium-name", arg("name", player.getUsername())));
                return;
            }
            if (users.findByPremiumUuid(profile.get().id()).isPresent()) {
                player.sendMessage(messages().get("premium.already-linked", arg("name", player.getUsername())));
                return;
            }
            resolver.requestPremiumCheck(player.getUsername());
            sessions.delete(user.uuid());
            player.disconnect(messages().get("kick.premium-rejoin"));
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
        if (!user.hasPassword()) {
            player.sendMessage(messages().get("premium.need-password"));
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
            users.update(user);
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
        plugin.async(() -> {
            HashedPassword hash = user.password();
            if (hash != null && !plugin.hashers().verify(password, hash)) {
                player.sendMessage(messages().get("auth.wrong-password", arg("remaining", "-")));
                return;
            }
            users.delete(user.uuid());
            player.disconnect(messages().get("kick.unregistered"));
        });
    }

    // ---- Shared with the API and admin command ----

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
        PlayerAuthState state = states.get(uuid);
        if (state != null) {
            state.user(user);
        }
    }

    private void authenticate(Player player, PlayerAuthState state, AuthReason reason) {
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
        if (plugin.isUnsafePassword(password)) {
            return messages().get("auth.password-unsafe");
        }
        return null;
    }

    private Void fail(Player player, Throwable error) {
        plugin.logger().error("Authentication action failed for {}", player.getUsername(), error);
        player.sendMessage(messages().get("auth.error"));
        return null;
    }

    public void purgeSessionsLater() {
        proxy.getScheduler().buildTask(plugin, () -> plugin.async(() -> sessions.purgeExpired(Instant.now())))
                .delay(1, TimeUnit.MINUTES)
                .repeat(1, TimeUnit.HOURS)
                .schedule();
    }

    public static String ip(Player player) {
        return player.getRemoteAddress().getAddress().getHostAddress();
    }
}
