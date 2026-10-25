package dev.lndf.logonmc.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Uuids {

    private Uuids() {
    }

    /**
     * The UUID offline-mode servers give a player with this name.
     */
    public static UUID offline(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses a UUID written without dashes, as the Mojang API returns them.
     */
    public static UUID fromUndashed(String undashed) {
        if (undashed.length() != 32) {
            throw new IllegalArgumentException("Not an undashed UUID: " + undashed);
        }
        return new UUID(Long.parseUnsignedLong(undashed, 0, 16, 16), Long.parseUnsignedLong(undashed, 16, 32, 16));
    }
}
