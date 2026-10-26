package dev.lndf.logonmc.crypto;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Hashes new passwords with the configured algorithm and verifies hashes of any known algorithm.
 * Hashing runs on {@code executor} while the caller blocks. {@code abandoned} is checked right
 * before the hash starts; if true, the hash is skipped with {@link CancellationException}.
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
        return hash(password, () -> false);
    }

    public HashedPassword hash(String password, BooleanSupplier abandoned) {
        return run(() -> hashers.get(preferred).hash(password), abandoned);
    }

    public boolean verify(String password, HashedPassword hashed) {
        return verify(password, hashed, () -> false);
    }

    public boolean verify(String password, HashedPassword hashed, BooleanSupplier abandoned) {
        return run(() -> hashers.get(hashed.algorithm()).verify(password, hashed), abandoned);
    }

    /**
     * Whether a hash should be replaced after a successful login: a different algorithm than the
     * configured one, or weaker parameters.
     */
    public boolean needsRehash(HashedPassword hashed) {
        return hashed.algorithm() != preferred || hashers.get(preferred).needsRehash(hashed);
    }

    private <T> T run(Supplier<T> task, BooleanSupplier abandoned) {
        Supplier<T> checked = () -> {
            if (abandoned.getAsBoolean()) {
                throw new CancellationException("Hash abandoned before it started");
            }
            return task.get();
        };
        try {
            return CompletableFuture.supplyAsync(checked, executor).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
