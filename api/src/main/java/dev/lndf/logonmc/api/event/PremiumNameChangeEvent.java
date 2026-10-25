package dev.lndf.logonmc.api.event;

import java.util.UUID;

/**
 * Fired when the owner of a linked account joins with a new Mojang name and LogonMC has updated
 * the account. Accounts that aren't linked can't change name.
 * Fires during login, before the player object exists.
 */
public final class PremiumNameChangeEvent {

    private final UUID uuid;
    private final String oldName;
    private final String newName;

    /**
     * Created by LogonMC; plugins only receive this event.
     *
     * @param uuid profile UUID of the account
     * @param oldName name the account last joined with
     * @param newName new Mojang name
     */
    public PremiumNameChangeEvent(UUID uuid, String oldName, String newName) {
        this.uuid = uuid;
        this.oldName = oldName;
        this.newName = newName;
    }

    /**
     * {@return profile UUID of the account}
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * {@return name the account last joined with}
     */
    public String getOldName() {
        return oldName;
    }

    /**
     * {@return new Mojang name, in its exact capitalization}
     */
    public String getNewName() {
        return newName;
    }
}
