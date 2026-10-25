package dev.lndf.logonmc.mojang;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.util.Ips;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Caps the Mojang lookups joins from one IP can cause, so a single client can't get the proxy
 * rate limited by Mojang. Fixed windows per IP; settings are read on every call.
 */
public final class LookupLimiter {

    private record Window(int count, Instant start) {
    }

    private final Supplier<PluginConfig.LookupLimit> settings;
    // Expiry only drops dead entries; whether a window is still open is decided from Window.start.
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(1))
            .maximumSize(100_000)
            .build();

    public LookupLimiter(Supplier<PluginConfig.LookupLimit> settings) {
        this.settings = settings;
    }

    /**
     * Counts one lookup for this IP.
     *
     * @return false if the IP has used up its lookups for the current window
     */
    public boolean tryAcquire(InetAddress ip) {
        PluginConfig.LookupLimit limit = settings.get();
        if (limit.maxLookupsPerIp() <= 0) {
            return true;
        }
        Instant now = Instant.now();
        AtomicBoolean allowed = new AtomicBoolean();
        windows.asMap().compute(Ips.limitKey(ip), (key, window) -> {
            if (window == null || !now.isBefore(window.start().plus(limit.window()))) {
                allowed.set(true);
                return new Window(1, now);
            }
            if (window.count() < limit.maxLookupsPerIp()) {
                allowed.set(true);
                return new Window(window.count() + 1, window.start());
            }
            return window;
        });
        return allowed.get();
    }
}
