package dev.lndf.logonmc.mojang;

import java.util.Optional;

public interface ProfileLookup {

    /**
     * Finds the premium account with this name (case-insensitive). Blocks.
     *
     * @throws LookupException if the API could not be reached or rate limited the request
     */
    Optional<MojangProfile> lookup(String name) throws LookupException;

    default boolean isCached(String name) {
        return false;
    }

    final class LookupException extends Exception {
        public LookupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
