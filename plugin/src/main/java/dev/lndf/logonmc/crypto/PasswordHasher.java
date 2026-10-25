package dev.lndf.logonmc.crypto;

public interface PasswordHasher {

    HashAlgorithm algorithm();

    HashedPassword hash(String password);

    boolean verify(String password, HashedPassword hashed);

    /**
     * Whether a hash made by this algorithm uses weaker parameters than it would today.
     */
    default boolean needsRehash(HashedPassword hashed) {
        return false;
    }
}
