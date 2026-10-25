package dev.lndf.logonmc.crypto;

import java.util.EnumMap;
import java.util.Map;

/**
 * Hashes new passwords with the configured algorithm and verifies hashes of any known algorithm.
 */
public final class Hashers {

    private final Map<HashAlgorithm, PasswordHasher> hashers = new EnumMap<>(HashAlgorithm.class);
    private final HashAlgorithm preferred;

    public Hashers(HashAlgorithm preferred) {
        this.preferred = preferred;
        register(new Argon2Hasher());
        register(new BCryptHasher());
    }

    private void register(PasswordHasher hasher) {
        hashers.put(hasher.algorithm(), hasher);
    }

    public HashedPassword hash(String password) {
        return hashers.get(preferred).hash(password);
    }

    public boolean verify(String password, HashedPassword hashed) {
        return hashers.get(hashed.algorithm()).verify(password, hashed);
    }

    /**
     * Whether a hash should be replaced after a successful login: a different algorithm than the
     * configured one, or weaker parameters.
     */
    public boolean needsRehash(HashedPassword hashed) {
        return hashed.algorithm() != preferred || hashers.get(preferred).needsRehash(hashed);
    }
}
