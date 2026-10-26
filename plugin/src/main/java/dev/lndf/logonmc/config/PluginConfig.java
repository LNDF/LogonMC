package dev.lndf.logonmc.config;

import dev.lndf.logonmc.crypto.HashAlgorithm;
import dev.lndf.logonmc.db.Dialect;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.kyori.adventure.bossbar.BossBar;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * Typed view of config.yml. Missing keys fall back to the same defaults as the bundled file.
 */
public record PluginConfig(
        Database database,
        Servers servers,
        Auth auth,
        Session session,
        Names names,
        Premium premium
) {

    public record Database(Dialect type, String host, int port, String name, String user, String password,
                           String properties, int poolSize) {
    }

    public record Servers(String auth, List<String> lobby, boolean rememberLastServer) {
    }

    public record Auth(Duration loginTimeout, int maxAttempts, Duration wrongPasswordDelay, Lockout lockout,
                       int passwordMinLength, int passwordMaxLength, HashAlgorithm hashAlgorithm, int hashThreads,
                       int hashQueueSize, Set<String> allowedCommands, Duration reminderInterval, UnsafePasswords unsafePasswords,
                       BossBarSettings bossBar) {
    }

    /** Limits of 0 are disabled. */
    public record Lockout(int maxFailuresPerIp, int maxFailuresPerAccount, Duration duration) {
    }

    public record BossBarSettings(boolean enabled, BossBar.Color color, BossBar.Overlay overlay) {
    }

    public record UnsafePasswords(boolean enabled, boolean caseSensitive) {
    }

    public record Session(boolean enabled, Duration duration) {
    }

    public record Names(Pattern pattern, boolean caseCheck) {
    }

    public record Premium(boolean protectPremiumNames, boolean switchUuidOnPremium, boolean suggestPremium,
                          boolean denyOnMojangError, Duration mojangCache, LookupLimit lookupLimit,
                          LookupSources lookupSources) {
    }

    /**
     * @param addresses local addresses to send Mojang lookups from; empty for the default route
     * @param proxies URL templates with {@code {name}}, answering like the Mojang API
     */
    public record LookupSources(List<InetAddress> addresses, List<String> proxies) {
    }

    /** A limit of 0 is disabled. */
    public record LookupLimit(int maxLookupsPerIp, Duration window) {
    }

    public static PluginConfig load(ConfigurationNode root) throws SerializationException {
        ConfigurationNode db = root.node("database");
        ConfigurationNode mysql = db.node("mysql");
        ConfigurationNode servers = root.node("servers");
        ConfigurationNode auth = root.node("auth");
        ConfigurationNode session = root.node("session");
        ConfigurationNode names = root.node("names");
        ConfigurationNode premium = root.node("premium");

        return new PluginConfig(
                new Database(
                        enumValue(Dialect.class, db.node("type").getString("H2")),
                        mysql.node("host").getString("localhost"),
                        mysql.node("port").getInt(3306),
                        mysql.node("database").getString("logonmc"),
                        mysql.node("user").getString("root"),
                        mysql.node("password").getString(""),
                        mysql.node("properties").getString(""),
                        Math.max(1, db.node("pool-size").getInt(10))
                ),
                new Servers(
                        servers.node("auth").getString("limbo"),
                        servers.node("lobby").getList(String.class, List.of("lobby")),
                        servers.node("remember-last-server").getBoolean(true)
                ),
                new Auth(
                        Duration.ofSeconds(auth.node("login-timeout-seconds").getLong(60)),
                        Math.max(1, auth.node("max-attempts").getInt(5)),
                        Duration.ofMillis(Math.max(0, auth.node("wrong-password-delay-millis").getLong(1000))),
                        new Lockout(
                                Math.max(0, auth.node("lockout", "max-failures-per-ip").getInt(10)),
                                Math.max(0, auth.node("lockout", "max-failures-per-account").getInt(20)),
                                // At most a day, the lifetime of LoginThrottle entries.
                                Duration.ofMinutes(Math.clamp(auth.node("lockout", "lockout-minutes").getLong(15), 1, 1440))
                        ),
                        auth.node("password-min-length").getInt(6),
                        auth.node("password-max-length").getInt(64),
                        enumValue(HashAlgorithm.class, auth.node("hash-algorithm").getString("ARGON2ID")),
                        hashThreads(auth.node("hash-threads").getInt(0)),
                        Math.max(1, auth.node("hash-queue-size").getInt(256)),
                        auth.node("allowed-commands").getList(String.class, List.of("login", "l", "register", "reg"))
                                .stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                        Duration.ofSeconds(Math.max(1, auth.node("reminder-interval-seconds").getLong(10))),
                        new UnsafePasswords(
                                auth.node("unsafe-passwords", "enabled").getBoolean(true),
                                auth.node("unsafe-passwords", "case-sensitive").getBoolean(false)
                        ),
                        new BossBarSettings(
                                auth.node("boss-bar", "enabled").getBoolean(true),
                                enumValue(BossBar.Color.class, auth.node("boss-bar", "color").getString("YELLOW")),
                                enumValue(BossBar.Overlay.class, auth.node("boss-bar", "overlay").getString("PROGRESS"))
                        )
                ),
                new Session(
                        session.node("enabled").getBoolean(true),
                        Duration.ofMinutes(session.node("duration-minutes").getLong(30))
                ),
                new Names(
                        Pattern.compile(names.node("pattern").getString("^[A-Za-z0-9_]{3,16}$")),
                        names.node("case-check").getBoolean(true)
                ),
                new Premium(
                        premium.node("protect-premium-names").getBoolean(true),
                        premium.node("switch-uuid-on-premium").getBoolean(false),
                        premium.node("suggest-premium").getBoolean(true),
                        premium.node("deny-on-mojang-error").getBoolean(true),
                        Duration.ofMinutes(Math.max(1, premium.node("mojang-cache-minutes").getLong(10))),
                        new LookupLimit(
                                Math.max(0, premium.node("lookup-limit", "max-lookups-per-ip").getInt(10)),
                                // At most a day, the lifetime of LookupLimiter entries.
                                Duration.ofMinutes(Math.clamp(premium.node("lookup-limit", "window-minutes").getLong(10), 1, 1440))
                        ),
                        new LookupSources(
                                localAddresses(premium.node("lookup-addresses").getList(String.class, List.of())),
                                proxyUrls(premium.node("lookup-proxies").getList(String.class, List.of()))
                        )
                )
        );
    }

    private static List<InetAddress> localAddresses(List<String> values) throws SerializationException {
        List<InetAddress> addresses = new ArrayList<>();
        for (String value : values) {
            InetAddress address;
            try {
                address = InetAddress.ofLiteral(value.trim());
            } catch (IllegalArgumentException e) {
                throw new SerializationException("lookup-addresses: '" + value + "' is not an IP address");
            }
            // Binding fails for an address this machine doesn't have, which would fail every lookup.
            try (Socket socket = new Socket()) {
                socket.bind(new InetSocketAddress(address, 0));
            } catch (IOException e) {
                throw new SerializationException("lookup-addresses: " + value + " is not an address of this machine");
            }
            addresses.add(address);
        }
        return List.copyOf(addresses);
    }

    private static List<String> proxyUrls(List<String> values) throws SerializationException {
        List<String> urls = new ArrayList<>();
        for (String value : values) {
            String url = value.trim();
            if (!url.contains("{name}")) {
                throw new SerializationException("lookup-proxies: '" + url + "' has no {name}");
            }
            try {
                String scheme = new URI(url.replace("{name}", "x")).getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                    throw new SerializationException("lookup-proxies: '" + url + "' is not an http(s) URL");
                }
            } catch (URISyntaxException e) {
                throw new SerializationException("lookup-proxies: '" + url + "' is not a valid URL");
            }
            urls.add(url);
        }
        return List.copyOf(urls);
    }

    private static int hashThreads(int configured) {
        return configured > 0 ? configured : Runtime.getRuntime().availableProcessors();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) throws SerializationException {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SerializationException("Unknown " + type.getSimpleName() + " '" + value + "'");
        }
    }
}
