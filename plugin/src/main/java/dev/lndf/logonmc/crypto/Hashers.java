package dev.lndf.logonmc.crypto;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Hashes new passwords with the configured algorithm and verifies hashes of any known algorithm.
 * Hashing runs on {@code executor}, a bounded pool, so a flood of password commands queues up
 * instead of running thousands of memory-hungry hashes at once. The calling thread blocks.
 */
public final class Hashers {

    private final Map<HashAlgorithm, PasswordHasher> hashers = new EnumMap<>(HashAlgorithm.class);
    private final HashAlgorithm preferred;
    private final Executor executor;

    public Hashers(HashAlgorithm preferred, Executor executor) {
        this.preferred = preferred;
        this.executor = executor;
        register(new Argon2Hasher());
        register(new BCryptHasher());
    }

    private void register(PasswordHasher hasher) {
        hashers.put(hasher.algorithm(), hasher);
    }

    public HashedPassword hash(String password) {
        return run(() -> hashers.get(preferred).hash(password));
    }

    public boolean verify(String password, HashedPassword hashed) {
        return run(() -> hashers.get(hashed.algorithm()).verify(password, hashed));
    }

    /**
     * Whether a hash should be replaced after a successful login: a different algorithm than the
     * configured one, or weaker parameters.
     */
    public boolean needsRehash(HashedPassword hashed) {
        return hashed.algorithm() != preferred || hashers.get(preferred).needsRehash(hashed);
    }

    private <T> T run(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, executor).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
