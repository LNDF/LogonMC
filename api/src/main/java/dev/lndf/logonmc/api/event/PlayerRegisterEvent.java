package dev.lndf.logonmc.api.event;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.Objects;

/**
 * Fired when a player runs /register with a valid password, before the account is stored.
 * Deny the result to block the registration; the reason component is shown to the player.
 * <p>Not fired when LogonMC refuses the registration itself, such as a protected premium name
 * registered by a player who joined in offline mode.
 */
public final class PlayerRegisterEvent implements ResultedEvent<ResultedEvent.ComponentResult> {

    private final Player player;
    private ComponentResult result = ComponentResult.allowed();

    /**
     * Created by LogonMC; plugins only receive this event.
     *
     * @param player the player registering
     */
    public PlayerRegisterEvent(Player player) {
        this.player = player;
    }

    /**
     * {@return the player the event is about}
     */
    public Player getPlayer() {
        return player;
    }

    @Override
    public ComponentResult getResult() {
        return result;
    }

    @Override
    public void setResult(ComponentResult result) {
        this.result = Objects.requireNonNull(result);
    }
}
