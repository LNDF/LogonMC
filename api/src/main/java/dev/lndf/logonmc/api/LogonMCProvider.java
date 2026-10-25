package dev.lndf.logonmc.api;

import org.jspecify.annotations.Nullable;

/**
 * Static holder for the {@link LogonMC} instance.
 */
public final class LogonMCProvider {

    private static volatile @Nullable LogonMC instance;

    private LogonMCProvider() {
    }

    /**
     * Returns the API instance.
     *
     * @return the API
     * @throws IllegalStateException if LogonMC is not loaded yet
     */
    public static LogonMC get() {
        LogonMC api = instance;
        if (api == null) {
            throw new IllegalStateException("LogonMC is not loaded. Add @Dependency(id = \"logonmc\") to your plugin.");
        }
        return api;
    }

    /**
     * Internal: called by LogonMC on startup and shutdown.
     *
     * @param api the instance, or {@code null} on shutdown
     */
    public static void register(@Nullable LogonMC api) {
        instance = api;
    }
}
