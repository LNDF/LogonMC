package dev.lndf.logonmc.api.event;

import com.velocitypowered.api.event.ResultedEvent;
import java.util.Objects;
import java.util.UUID;

/**
 * Fired when an account is linked to its Mojang account and LogonMC is about to change its profile
 * UUID from {@code oldUuid} to the Mojang UUID ({@code premium.switch-uuid-on-premium: true}).
 *
 * <p>Plugins storing data by UUID should move it from the old UUID to the new one. Denying the
 * result keeps the old profile UUID; the account is still linked.
 *
 * <p>It fires during the join that completes /premium, before the player object exists, or when
 * an admin links the account with /logonmc premium (the player is then disconnected if online).
 */
public final class PremiumMigrationEvent implements ResultedEvent<ResultedEvent.GenericResult> {

    private final String username;
    private final UUID oldUuid;
    private final UUID newUuid;
    private GenericResult result = GenericResult.allowed();

    /**
     * Created by LogonMC; plugins only receive this event.
     *
     * @param username name of the account being linked
     * @param oldUuid current profile UUID
     * @param newUuid Mojang UUID the profile is switching to
     */
    public PremiumMigrationEvent(String username, UUID oldUuid, UUID newUuid) {
        this.username = username;
        this.oldUuid = oldUuid;
        this.newUuid = newUuid;
    }

    /**
     * {@return name of the account being linked}
     */
    public String getUsername() {
        return username;
    }

    /**
     * {@return profile UUID the account has now; move data away from it}
     */
    public UUID getOldUuid() {
        return oldUuid;
    }

    /**
     * {@return Mojang UUID the account will use from now on; move data to it}
     */
    public UUID getNewUuid() {
        return newUuid;
    }

    @Override
    public GenericResult getResult() {
        return result;
    }

    @Override
    public void setResult(GenericResult result) {
        this.result = Objects.requireNonNull(result);
    }
}
