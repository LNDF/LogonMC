package dev.lndf.logonmc.api;

/**
 * How a player became authenticated.
 */
public enum AuthReason {
    /** Typed the correct password with /login. */
    LOGIN,
    /** Created an account with /register. */
    REGISTER,
    /** Joined a linked account through Mojang authentication. */
    PREMIUM,
    /** Rejoined from the same IP within the session window. */
    SESSION,
    /** Another plugin called {@link LogonMC#forceLogin}, or an admin used /logonmc forcelogin. */
    API
}
