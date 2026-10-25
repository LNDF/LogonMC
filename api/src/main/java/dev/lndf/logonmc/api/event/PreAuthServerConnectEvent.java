package dev.lndf.logonmc.api.event;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jspecify.annotations.Nullable;

/**
 * Fired after a player authenticates on the auth server, before LogonMC sends them on.
 * Change the target to pick another server, or set it to {@code null} to keep the player where
 * they are and handle the transfer yourself.
 */
public final class PreAuthServerConnectEvent {

    private final Player player;
    private @Nullable RegisteredServer target;

    /**
     * Created by LogonMC; plugins only receive this event.
     *
     * @param player the authenticated player
     * @param target the server LogonMC would send them to, or {@code null} if none is available
     */
    public PreAuthServerConnectEvent(Player player, @Nullable RegisteredServer target) {
        this.player = player;
        this.target = target;
    }

    /**
     * {@return the player the event is about}
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * {@return the server the player will be sent to, or {@code null} to keep them where they are}
     */
    public @Nullable RegisteredServer getTarget() {
        return target;
    }

    /**
     * Changes where the player is sent.
     *
     * @param target the server to send the player to, or {@code null} to keep them where they are
     */
    public void setTarget(@Nullable RegisteredServer target) {
        this.target = target;
    }
}
