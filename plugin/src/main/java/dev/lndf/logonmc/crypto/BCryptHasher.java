package dev.lndf.logonmc.crypto;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.nio.charset.StandardCharsets;

/**
 * BCrypt ($2a$/$2b$/$2y$ hashes), cost 12 for new hashes.
 */
public final class BCryptHasher implements PasswordHasher {

    private static final int COST = 12;

    @Override
    public HashAlgorithm algorithm() {
        return HashAlgorithm.BCRYPT;
    }

    @Override
    public HashedPassword hash(String password) {
        // BCrypt only uses the first 72 bytes; the configured max length keeps typical passwords below that.
        String hash = BCrypt.withDefaults().hashToString(COST, password.toCharArray());
        return new HashedPassword(hash, HashAlgorithm.BCRYPT);
    }

    @Override
    public boolean verify(String password, HashedPassword hashed) {
        try {
            return BCrypt.verifyer().verify(password.getBytes(StandardCharsets.UTF_8),
                    hashed.hash().getBytes(StandardCharsets.UTF_8)).verified;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean needsRehash(HashedPassword hashed) {
        String[] parts = hashed.hash().split("\\$");
        try {
            return parts.length < 3 || Integer.parseInt(parts[2]) < COST;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
