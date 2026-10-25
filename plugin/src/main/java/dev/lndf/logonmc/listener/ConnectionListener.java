package dev.lndf.logonmc.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.lndf.logonmc.LogonMCPlugin;
import dev.lndf.logonmc.auth.AuthService;
import dev.lndf.logonmc.auth.LoginResolver;
import dev.lndf.logonmc.db.StoredUser;
import java.util.Optional;
import java.util.UUID;

import static dev.lndf.logonmc.config.Messages.arg;

/**
 * The join flow: pre-login decision, profile UUID mapping, auto-login and initial server routing.
 */
public final class ConnectionListener {

    private final LogonMCPlugin plugin;
    private final LoginResolver resolver;
    private final AuthService auth;

    public ConnectionListener(LogonMCPlugin plugin, LoginResolver resolver, AuthService auth) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.auth = auth;
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            try {
                LoginResolver.Decision decision = resolver.decide(event.getUsername());
                event.setResult(switch (decision.mode()) {
                    case ONLINE -> PreLoginEvent.PreLoginComponentResult.forceOnlineMode();
                    case OFFLINE -> PreLoginEvent.PreLoginComponentResult.forceOfflineMode();
                    case DENY -> PreLoginEvent.PreLoginComponentResult.denied(plugin.messages().get(
                            decision.messageKey(), arg("correct", decision.correctName() == null ? "" : decision.correctName())));
                });
            } catch (RuntimeException e) {
                plugin.logger().error("Pre-login check failed for {}", event.getUsername(), e);
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(plugin.messages().get("kick.database-error")));
            }
        });
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public EventTask onGameProfile(GameProfileRequestEvent event) {
        return EventTask.async(() -> {
            String name = event.getUsername();
            Optional<StoredUser> user;
            UUID profileId;
            try {
                user = event.isOnlineMode()
                        ? resolver.resolveOnline(name, event.getOriginalProfile().getId())
                        : resolver.resolveOffline(name);
                profileId = user.isPresent() ? user.get().uuid() : resolver.newAccountUuid(name);
            } catch (LoginResolver.LoginDeniedException e) {
                // The connection can't be refused here; LoginEvent refuses it.
                plugin.logger().info("Refusing {}: {}", name, e.getMessage());
                auth.rememberProfileRefusal(event.getGameProfile().getId(), e.messageKey());
                return;
            } catch (RuntimeException e) {
                plugin.logger().error("Profile lookup failed for {}", name, e);
                auth.rememberProfileRefusal(event.getGameProfile().getId(), "kick.database-error");
                return;
            }
            event.setGameProfile(event.getGameProfile().withId(profileId).withName(name));
            auth.rememberProfile(profileId, user.orElse(null));
        });
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            try {
                auth.initialize(event.getPlayer());
            } catch (LoginResolver.LoginDeniedException e) {
                event.setResult(ResultedEvent.ComponentResult.denied(plugin.messages().get(e.messageKey())));
            } catch (RuntimeException e) {
                plugin.logger().error("Could not load account of {}", event.getPlayer().getUsername(), e);
                event.setResult(ResultedEvent.ComponentResult.denied(plugin.messages().get("kick.database-error")));
            }
        });
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        auth.postLogin(event.getPlayer());
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> target = auth.initialServer(event.getPlayer());
        if (target.isPresent()) {
            event.setInitialServer(target.get());
        } else if (!auth.isAuthenticated(event.getPlayer())) {
            plugin.logger().error("Auth server '{}' is not registered in velocity.toml", plugin.config().servers().auth());
            event.getPlayer().disconnect(plugin.messages().get("kick.no-auth-server"));
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        auth.serverPostConnect(event.getPlayer(), event.getPreviousServer() == null);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        auth.serverConnected(event.getPlayer(), event.getServer());
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onDisconnect(DisconnectEvent event) {
        auth.disconnect(event.getPlayer());
    }
}
