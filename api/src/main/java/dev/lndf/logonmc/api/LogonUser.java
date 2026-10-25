package dev.lndf.logonmc.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only snapshot of a stored account.
 *
 * @param uuid profile UUID used on backend servers
 * @param premiumUuid Mojang UUID the account is linked to, or {@code null} for a cracked account
 * @param lastNickname last name the player joined with, in its exact capitalization. A cracked
 *     account owns this name. For a linked account it is only a record and may be out of date
 *     (and shared with another account) after a Mojang name change, until they join again.
 * @param hasPassword whether the account has a password set. Every account gets one on /register.
 * @param joined first join
 * @param lastSeen last disconnect
 * @param lastServer last backend server the player was on
 * @param lastIp IP address of the last successful authentication
 */
public record LogonUser(
        UUID uuid,
        @Nullable UUID premiumUuid,
        String lastNickname,
        boolean hasPassword,
        @Nullable Instant joined,
        @Nullable Instant lastSeen,
        @Nullable String lastServer,
        @Nullable String lastIp
) {

    /**
     * Whether the account is linked to a Mojang account with /premium.
     *
     * @return {@code true} if {@link #premiumUuid()} is set
     */
    public boolean isLinked() {
        return premiumUuid != null;
    }
}
