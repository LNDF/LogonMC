package dev.lndf.logonmc.crypto;

import java.util.Locale;

/**
 * A password hash in the self-describing string format of its algorithm (PHC string for Argon2id,
 * modular crypt for BCrypt), so parameters and salt travel with the hash.
 */
public record HashedPassword(String hash, HashAlgorithm algorithm) {

    public static HashedPassword of(String hash, String algorithm) {
        return new HashedPassword(hash, HashAlgorithm.valueOf(algorithm.toUpperCase(Locale.ROOT)));
    }
}
