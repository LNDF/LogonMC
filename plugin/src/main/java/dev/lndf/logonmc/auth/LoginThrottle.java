package dev.lndf.logonmc.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.util.Ips;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wrong passwords per IP and per account, kept across reconnects. A lockout lasts until the
 * lockout duration passes without another wrong password.
 */
public final class LoginThrottle {

    private record Failures(int count, Instant last) {
    }

    private final Supplier<PluginConfig.Lockout> settings;
    // Expiry only drops dead entries; whether a lockout still holds is decided from Failures.last.
    private final Cache<String, Failures> failures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(1))
            .maximumSize(100_000)
            .build();

    public LoginThrottle(Supplier<PluginConfig.Lockout> settings) {
        this.settings = settings;
    }

    public boolean isLockedOut(UUID account, InetAddress ip) {
        PluginConfig.Lockout lockout = settings.get();
        Instant now = Instant.now();
        return reached(ipKey(ip), lockout.maxFailuresPerIp(), lockout, now)
                || reached(accountKey(account), lockout.maxFailuresPerAccount(), lockout, now);
    }

    public void recordFailure(UUID account, InetAddress ip) {
        PluginConfig.Lockout lockout = settings.get();
        Instant now = Instant.now();
        increment(ipKey(ip), lockout, now);
        increment(accountKey(account), lockout, now);
    }

    // The IP's count stays, or an attacker could reset it by logging in to their own account.
    public void recordSuccess(UUID account) {
        failures.invalidate(accountKey(account));
    }

    private boolean reached(String key, int limit, PluginConfig.Lockout lockout, Instant now) {
        Failures current = failures.getIfPresent(key);
        return limit > 0 && current != null && current.count() >= limit && !expired(current, lockout, now);
    }

    private void increment(String key, PluginConfig.Lockout lockout, Instant now) {
        failures.asMap().compute(key, (k, current) ->
                current == null || expired(current, lockout, now)
                        ? new Failures(1, now)
                        : new Failures(current.count() + 1, now));
    }

    private static boolean expired(Failures failures, PluginConfig.Lockout lockout, Instant now) {
        return !now.isBefore(failures.last().plus(lockout.duration()));
    }

    private static String ipKey(InetAddress ip) {
        return "ip:" + Ips.limitKey(ip);
    }

    private static String accountKey(UUID account) {
        return "account:" + account;
    }
}
