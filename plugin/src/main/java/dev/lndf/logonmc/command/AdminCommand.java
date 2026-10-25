package dev.lndf.logonmc.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.lndf.logonmc.LogonMCPlugin;
import dev.lndf.logonmc.auth.AuthService;
import dev.lndf.logonmc.auth.LoginResolver;
import dev.lndf.logonmc.config.Messages;
import dev.lndf.logonmc.db.SessionRepository;
import dev.lndf.logonmc.db.StoredUser;
import dev.lndf.logonmc.db.UserRepository;
import dev.lndf.logonmc.mojang.MojangProfile;
import dev.lndf.logonmc.mojang.ProfileLookup;
import dev.lndf.logonmc.util.Uuids;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

import static dev.lndf.logonmc.config.Messages.arg;

/**
 * /logonmc: administration. Each subcommand needs {@code logonmc.admin.<subcommand>};
 * {@code logonmc.admin} grants all of them.
 */
public final class AdminCommand implements SimpleCommand {

    public static final String PERMISSION = "logonmc.admin";
    private static final String PERMISSION_PREFIX = PERMISSION + ".";
    private static final List<String> SUBCOMMANDS =
            List.of("reload", "info", "forcelogin", "unregister", "setpassword", "premium", "cracked");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final LogonMCPlugin plugin;
    private final AuthService auth;
    private final LoginResolver resolver;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final ProfileLookup mojang;

    public AdminCommand(LogonMCPlugin plugin, AuthService auth, LoginResolver resolver, UserRepository users,
                        SessionRepository sessions, ProfileLookup mojang) {
        this.plugin = plugin;
        this.auth = auth;
        this.resolver = resolver;
        this.users = users;
        this.sessions = sessions;
        this.mojang = mojang;
    }

    /** Shown to anyone allowed at least one subcommand. */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return SUBCOMMANDS.stream().anyMatch(sub -> allowed(invocation.source(), sub));
    }

    private static boolean allowed(CommandSource source, String sub) {
        return source.hasPermission(PERMISSION) || source.hasPermission(PERMISSION_PREFIX + sub);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(prefix) && allowed(invocation.source(), s))
                    .toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && !sub.equals("reload") && SUBCOMMANDS.contains(sub) && allowed(invocation.source(), sub)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.proxy().getAllPlayers().stream().map(Player::getUsername)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        Messages msg = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        int expected = switch (sub) {
            case "reload" -> 1;
            case "info", "forcelogin", "unregister", "premium", "cracked" -> 2;
            case "setpassword" -> 3;
            default -> -1;
        };
        if (expected != -1 && !allowed(source, sub)) {
            source.sendMessage(msg.get("admin.no-permission"));
            return;
        }
        if (args.length != expected) {
            source.sendMessage(msg.get("admin.usage"));
            return;
        }
        plugin.async(() -> {
            switch (sub) {
                case "reload" -> reload(source);
                case "info" -> withUser(source, args[1], user -> info(source, user));
                case "forcelogin" -> forceLogin(source, args[1]);
                case "unregister" -> withUser(source, args[1], user -> unregister(source, user));
                case "setpassword" -> withUser(source, args[1], user -> setPassword(source, user, args[2]));
                case "premium" -> withUser(source, args[1], user -> premium(source, user));
                case "cracked" -> withUser(source, args[1], user -> cracked(source, user));
                default -> source.sendMessage(msg.get("admin.usage"));
            }
        });
    }

    private void reload(CommandSource source) {
        if (plugin.reload()) {
            source.sendMessage(plugin.messages().get("admin.reloaded"));
        } else {
            source.sendMessage(plugin.messages().get("auth.error"));
        }
    }

    private void info(CommandSource source, StoredUser user) {
        source.sendMessage(plugin.messages().get("admin.info",
                arg("name", user.lastNickname()),
                arg("uuid", user.uuid()),
                arg("premium", user.premiumUuid() == null ? "-" : user.premiumUuid()),
                arg("password", user.password() == null ? "-" : user.password().algorithm()),
                arg("joined", format(user.joined())),
                arg("last_seen", format(user.lastSeen())),
                arg("last_server", user.lastServer() == null ? "-" : user.lastServer()),
                arg("last_ip", user.lastIp() == null ? "-" : user.lastIp())));
    }

    private void forceLogin(CommandSource source, String name) {
        Optional<Player> player = plugin.proxy().getPlayer(name);
        if (player.isEmpty()) {
            source.sendMessage(plugin.messages().get("admin.player-not-online", arg("name", name)));
            return;
        }
        auth.forceLogin(player.get());
        source.sendMessage(plugin.messages().get("admin.forced-login", arg("name", player.get().getUsername())));
    }

    private void unregister(CommandSource source, StoredUser user) {
        users.delete(user.uuid());
        plugin.proxy().getPlayer(user.uuid()).ifPresent(p -> p.disconnect(plugin.messages().get("kick.unregistered")));
        source.sendMessage(plugin.messages().get("admin.unregistered", arg("name", user.lastNickname())));
    }

    private void setPassword(CommandSource source, StoredUser user, String password) {
        user.password(plugin.hashers().hash(password));
        users.update(user);
        sessions.delete(user.uuid());
        auth.refreshUser(user.uuid(), user);
        source.sendMessage(plugin.messages().get("admin.password-set", arg("name", user.lastNickname())));
    }

    /**
     * Links the account to the Mojang account of the same name without a handshake. The admin
     * vouches for ownership.
     */
    private void premium(CommandSource source, StoredUser user) {
        if (user.isLinked()) {
            source.sendMessage(plugin.messages().get("admin.already-linked", arg("name", user.lastNickname())));
            return;
        }
        Optional<MojangProfile> profile;
        try {
            profile = mojang.lookup(user.lastNickname());
        } catch (ProfileLookup.LookupException e) {
            source.sendMessage(plugin.messages().get("auth.error"));
            return;
        }
        if (profile.isEmpty()) {
            source.sendMessage(plugin.messages().get("premium.not-premium-name", arg("name", user.lastNickname())));
            return;
        }
        Optional<StoredUser> linked = users.findByPremiumUuid(profile.get().id());
        if (linked.isPresent()) {
            source.sendMessage(plugin.messages().get("premium.already-linked", arg("name", profile.get().name())));
            return;
        }
        UUID oldUuid = user.uuid();
        resolver.link(user, profile.get().name(), profile.get().id());
        sessions.delete(user.uuid());
        // The online player's state must hold this instance, or their disconnect writes the old row back.
        auth.refreshUser(oldUuid, user);
        plugin.proxy().getPlayer(oldUuid).ifPresent(p -> p.disconnect(plugin.messages().get("kick.premium-rejoin")));
        source.sendMessage(plugin.messages().get("admin.premium-set", arg("name", user.lastNickname())));
    }

    private void cracked(CommandSource source, StoredUser user) {
        if (!user.isLinked()) {
            source.sendMessage(plugin.messages().get("admin.not-linked", arg("name", user.lastNickname())));
            return;
        }
        Optional<StoredUser> owner = users.findCrackedByName(user.lastNickname());
        if (owner.isPresent()) {
            source.sendMessage(plugin.messages().get("premium.name-taken", arg("name", user.lastNickname())));
            return;
        }
        user.premiumUuid(null);
        users.update(user);
        auth.refreshUser(user.uuid(), user);
        plugin.proxy().getPlayer(user.uuid()).ifPresent(p -> p.disconnect(plugin.messages().get("kick.cracked-rejoin")));
        source.sendMessage(plugin.messages().get("admin.cracked-set", arg("name", user.lastNickname())));
    }

    /**
     * Runs {@code action} on the account an admin named. A UUID (profile or Mojang, with or without
     * dashes) selects exactly one account. A name selects the account {@link UserRepository#findByName}
     * returns, and lists any other accounts last seen with it, such as linked players who have since
     * changed their Mojang name, so the admin can pick one by UUID.
     */
    private void withUser(CommandSource source, String target, Consumer<StoredUser> action) {
        Messages msg = plugin.messages();
        UUID uuid = parseUuid(target);
        if (uuid != null) {
            Optional<StoredUser> user = users.findByUuid(uuid).or(() -> users.findByPremiumUuid(uuid));
            if (user.isEmpty()) {
                source.sendMessage(msg.get("admin.user-not-found", arg("name", target)));
                return;
            }
            action.accept(user.get());
            return;
        }
        List<StoredUser> matches = users.findAllByName(target);
        if (matches.isEmpty()) {
            source.sendMessage(msg.get("admin.user-not-found", arg("name", target)));
            return;
        }
        StoredUser selected = matches.getFirst();
        if (matches.size() > 1) {
            String others = matches.subList(1, matches.size()).stream()
                    .map(other -> other.uuid() + (other.isLinked() ? " (linked)" : ""))
                    .collect(Collectors.joining(", "));
            source.sendMessage(msg.get("admin.other-accounts",
                    arg("name", target), arg("uuid", selected.uuid()), arg("accounts", others)));
        }
        action.accept(selected);
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            if (value.length() == 36) {
                return UUID.fromString(value);
            }
            if (value.length() == 32) {
                return Uuids.fromUndashed(value);
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID: treat it as a name.
        }
        return null;
    }

    private static String format(@Nullable Instant instant) {
        return instant == null ? "-" : DATE.format(instant);
    }
}
