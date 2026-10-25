package dev.lndf.logonmc.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.lndf.logonmc.LogonMCPlugin;
import dev.lndf.logonmc.auth.AuthService;
import dev.lndf.logonmc.auth.PlayerAuthState;
import dev.lndf.logonmc.db.StoredUser;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;

/**
 * Player commands. They take raw arguments (SimpleCommand) rather than Brigadier strings so that
 * passwords may contain any character.
 */
public final class AuthCommands {

    private final LogonMCPlugin plugin;
    private final AuthService auth;

    public AuthCommands(LogonMCPlugin plugin, AuthService auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    public void register(CommandManager commands) {
        register(commands, "login", (player, args) -> {
            if (args.length != 1) {
                player.sendMessage(usage("/login <password>"));
                return;
            }
            auth.login(player, args[0]);
        }, "l", "log");

        register(commands, "register", (player, args) -> {
            if (args.length != 2) {
                player.sendMessage(usage("/register <password> <password>"));
                return;
            }
            auth.register(player, args[0], args[1]);
        }, "reg");

        register(commands, "changepassword", (player, args) -> {
            PlayerAuthState state = auth.state(player);
            StoredUser user = state == null ? null : state.user();
            boolean hasPassword = user != null && user.hasPassword();
            if (hasPassword && args.length == 2) {
                auth.changePassword(player, args[0], args[1]);
            } else if (!hasPassword && args.length == 1) {
                auth.changePassword(player, null, args[0]);
            } else {
                player.sendMessage(usage(hasPassword
                        ? "/changepassword <old password> <new password>"
                        : "/changepassword <new password>"));
            }
        }, "changepass");

        register(commands, "logout", (player, args) -> auth.logout(player));

        register(commands, "premium", (player, args) -> {
            if (args.length != 1) {
                player.sendMessage(plugin.messages().get("premium.confirm"));
                return;
            }
            auth.requestPremium(player, args[0]);
        });

        register(commands, "cracked", (player, args) -> auth.becomeCracked(player));

        register(commands, "unregister", (player, args) -> {
            if (args.length != 1) {
                player.sendMessage(usage("/unregister <password>"));
                return;
            }
            auth.unregister(player, args[0]);
        });
    }

    private void register(CommandManager commands, String name, BiConsumer<Player, String[]> action, String... aliases) {
        SimpleCommand command = invocation -> {
            if (invocation.source() instanceof Player player) {
                action.accept(player, invocation.arguments());
            } else {
                invocation.source().sendMessage(Component.text("Only players can use /" + name + "."));
            }
        };
        commands.register(commands.metaBuilder(name).aliases(aliases).plugin(plugin).build(), command);
    }

    private Component usage(String usage) {
        return plugin.messages().get("prefix").append(Component.text(usage));
    }
}
