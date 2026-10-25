package dev.lndf.logonmc.api;

import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point of the LogonMC API.
 *
 * <p>Every account is created with a password and starts as a cracked account. Its owner can link it
 * to their Mojang account with /premium; a linked account then joins through Mojang authentication
 * and logs in without a password. A premium player who never linked has an ordinary cracked account.
 *
 * <p>Obtain it with {@link LogonMCProvider#get()} after LogonMC has initialized. Declare
 * {@code @Dependency(id = "logonmc")} on your plugin so LogonMC loads first.
 *
 * <p>Methods returning {@link CompletableFuture} hit the database and complete off the calling
 * thread. Methods taking a {@link Player} answer from memory and are safe to call anywhere.
 */
public interface LogonMC {

    /**
     * Whether the player has passed authentication (password, linked account, session or API).
     *
     * @param player an online player
     * @return {@code true} once the player is logged in
     */
    boolean isAuthenticated(Player player);

    /**
     * Whether the player is on a linked account, which they joined through Mojang authentication.
     *
     * @param player an online player
     * @return {@code false} for cracked accounts, including premium players who haven't linked
     */
    boolean isLinked(Player player);

    /**
     * Whether the online player has an account.
     *
     * @param player an online player
     * @return {@code false} until an unregistered player runs /register
     */
    boolean isRegistered(Player player);

    /**
     * Looks up an account by its profile UUID, the UUID the player has on backend servers.
     *
     * @param uuid profile UUID
     * @return the account, or empty if none has this UUID
     */
    CompletableFuture<Optional<LogonUser>> getUser(UUID uuid);

    /**
     * Looks up an account by name, case-insensitively.
     *
     * <p>A cracked account owns its name, so it is returned if there is one. Linked accounts keep
     * the name they last joined with until they join again, so after a Mojang name change several
     * accounts can share a name; without a cracked owner, the most recently seen one is returned.
     * Use {@link #getUsers(String)} to get all of them.
     *
     * @param name player name, in any capitalization
     * @return the account, or empty if no account was last seen with this name
     */
    CompletableFuture<Optional<LogonUser>> getUser(String name);

    /**
     * Every account last seen with this name, case-insensitively.
     *
     * @param name player name, in any capitalization
     * @return the cracked owner first, then linked accounts from most recently seen; empty if none
     */
    CompletableFuture<List<LogonUser>> getUsers(String name);

    /**
     * Looks up an account by the Mojang UUID it is linked to.
     *
     * @param premiumUuid Mojang account UUID
     * @return the linked account, or empty if no account is linked to it
     */
    CompletableFuture<Optional<LogonUser>> getUserByPremiumUuid(UUID premiumUuid);

    /**
     * Authenticates the player without a password and sends them on from the auth server.
     * Does nothing if the player is already authenticated.
     *
     * @param player an online player
     * @return completes once the player is authenticated
     */
    CompletableFuture<Void> forceLogin(Player player);

    /**
     * Revokes the player's authentication and session, sending them back to the auth server.
     *
     * @param player an online player
     * @return completes once the player is logged out
     */
    CompletableFuture<Void> forceLogout(Player player);

    /**
     * Checks a password against the stored hash.
     *
     * @param uuid profile UUID of the account
     * @param password password to check
     * @return {@code true} if it matches; {@code false} if it doesn't, or the account doesn't exist
     *     or has no password
     */
    CompletableFuture<Boolean> checkPassword(UUID uuid, String password);

    /**
     * Sets a new password for an existing account and invalidates its sessions.
     *
     * @param uuid profile UUID of the account
     * @param password new password, stored hashed
     * @return {@code false} if the account does not exist
     */
    CompletableFuture<Boolean> setPassword(UUID uuid, String password);

    /**
     * Deletes the account and its sessions. An online owner is disconnected.
     *
     * @param uuid profile UUID of the account
     * @return {@code false} if the account does not exist
     */
    CompletableFuture<Boolean> unregister(UUID uuid);

    /**
     * Removes any stored session so the next join requires a password.
     *
     * @param uuid profile UUID of the account
     * @return completes once the session is removed
     */
    CompletableFuture<Void> invalidateSession(UUID uuid);
}
