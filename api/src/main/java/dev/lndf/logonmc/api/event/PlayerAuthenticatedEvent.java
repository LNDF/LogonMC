package dev.lndf.logonmc.api.event;

import com.velocitypowered.api.proxy.Player;
import dev.lndf.logonmc.api.AuthReason;

/**
 * Fired after a player has been authenticated, before they are sent on from the auth server.
 */
public final class PlayerAuthenticatedEvent {

    private final Player player;
    private final AuthReason reason;

    /**
     * Created by LogonMC; plugins only receive this event.
     *
     * @param player the authenticated player
     * @param reason how they authenticated
     */
    public PlayerAuthenticatedEvent(Player player, AuthReason reason) {
        this.player = player;
        this.reason = reason;
    }

    /**
     * {@return the player the event is about}
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * {@return how the player authenticated}
     */
    public AuthReason getReason() {
        return reason;
    }
}
