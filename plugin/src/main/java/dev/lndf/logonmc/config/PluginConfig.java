package dev.lndf.logonmc.config;

import dev.lndf.logonmc.crypto.HashAlgorithm;
import dev.lndf.logonmc.db.Dialect;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    public record Auth(Duration loginTimeout, int maxAttempts, int passwordMinLength, int passwordMaxLength,
                       HashAlgorithm hashAlgorithm, Set<String> allowedCommands, Duration reminderInterval,
                       UnsafePasswords unsafePasswords) {
    }

    public record UnsafePasswords(boolean enabled, boolean caseSensitive) {
    }

    public record Session(boolean enabled, Duration duration) {
    }

    public record Names(Pattern pattern, boolean caseCheck) {
    }

    public record Premium(boolean protectPremiumNames, boolean switchUuidOnPremium, boolean suggestPremium,
                          boolean denyOnMojangError, Duration mojangCache) {
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
                        auth.node("password-min-length").getInt(6),
                        auth.node("password-max-length").getInt(64),
                        enumValue(HashAlgorithm.class, auth.node("hash-algorithm").getString("ARGON2ID")),
                        auth.node("allowed-commands").getList(String.class, List.of("login", "l", "register", "reg"))
                                .stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                        Duration.ofSeconds(Math.max(1, auth.node("reminder-interval-seconds").getLong(10))),
                        new UnsafePasswords(
                                auth.node("unsafe-passwords", "enabled").getBoolean(true),
                                auth.node("unsafe-passwords", "case-sensitive").getBoolean(false)
                        )
                ),
                new Session(
                        session.node("enabled").getBoolean(true),
                        Duration.ofMinutes(session.node("duration-minutes").getLong(60))
                ),
                new Names(
                        Pattern.compile(names.node("pattern").getString("^[A-Za-z0-9_]{3,16}$")),
                        names.node("case-check").getBoolean(true)
                ),
                new Premium(
                        premium.node("protect-premium-names").getBoolean(true),
                        premium.node("switch-uuid-on-premium").getBoolean(false),
                        premium.node("suggest-premium").getBoolean(true),
                        premium.node("deny-on-mojang-error").getBoolean(false),
                        Duration.ofMinutes(Math.max(1, premium.node("mojang-cache-minutes").getLong(10)))
                )
        );
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) throws SerializationException {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SerializationException("Unknown " + type.getSimpleName() + " '" + value + "'");
        }
    }
}
