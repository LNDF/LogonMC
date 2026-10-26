package dev.lndf.logonmc.api.event;

import com.velocitypowered.api.proxy.Player;

/**
 * Fired when an authenticated player loses authentication while still online, through /logout
 * or {@link dev.lndf.logonmc.api.LogonMC#forceLogout}. LogonMC then sends the player back to the
 * auth server, or disconnects them if no auth server is available or the switch to it fails.
 */
public final class PlayerLogoutEvent {

    private final Player player;

    /**
     * Created by LogonMC; plugins only receive this event.
     *
     * @param player the player who lost authentication
     */
    public PlayerLogoutEvent(Player player) {
        this.player = player;
    }

    /**
     * {@return the player the event is about}
     */
    public Player getPlayer() {
        return player;
    }
}
