package dev.lndf.logonmc.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.lndf.logonmc.LogonMCPlugin;
import dev.lndf.logonmc.auth.AuthService;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps unauthenticated players on the auth server and away from commands.
 * Chat is not intercepted: the auth server (a limbo) doesn't relay it, and cancelling signed chat
 * on the proxy disconnects modern clients.
 */
public final class GuardListener {

    private final LogonMCPlugin plugin;
    private final AuthService auth;

    public GuardListener(LogonMCPlugin plugin, AuthService auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    // Each guard runs first, so other plugins see the refusal, and last, because a later plugin
    // may change the result (a lobby balancer, a command rewriter).

    @Subscribe(priority = Short.MAX_VALUE)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        guardServer(event);
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onServerPreConnectFinal(ServerPreConnectEvent event) {
        guardServer(event);
    }

    private void guardServer(ServerPreConnectEvent event) {
        if (auth.isAuthenticated(event.getPlayer())) {
            return;
        }
        // Velocity connects to the result's server, not the original one.
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isPresent() && !auth.isAuthServer(target.get())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onCommand(CommandExecuteEvent event) {
        guardCommand(event);
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onCommandFinal(CommandExecuteEvent event) {
        guardCommand(event);
    }

    private void guardCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player) || auth.isAuthenticated(player)
                || !event.getResult().isAllowed()) {
            return;
        }
        String command = event.getResult().getCommand().orElse(event.getCommand()).stripLeading();
        int space = command.indexOf(' ');
        String root = (space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT);
        if (!plugin.config().auth().allowedCommands().contains(root)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            player.sendMessage(plugin.messages().get("auth.blocked-command"));
        }
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onTabComplete(TabCompleteEvent event) {
        if (!auth.isAuthenticated(event.getPlayer())) {
            event.getSuggestions().clear();
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onKicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        if (!auth.isAuthenticated(player) && auth.isAuthServer(event.getServer())) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    event.getServerKickReason().orElse(plugin.messages().get("kick.no-auth-server"))));
        }
    }
}
