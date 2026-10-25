package dev.lndf.logonmc.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id with OWASP's baseline parameters (19 MiB, 2 iterations, 1 lane), stored as a PHC
 * string: {@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>}.
 */
public final class Argon2Hasher implements PasswordHasher {

    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private final SecureRandom random = new SecureRandom();

    @Override
    public HashAlgorithm algorithm() {
        return HashAlgorithm.ARGON2ID;
    }

    @Override
    public HashedPassword hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES);
        String encoded = "$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s".formatted(
                MEMORY_KIB, ITERATIONS, PARALLELISM, B64_ENCODER.encodeToString(salt), B64_ENCODER.encodeToString(hash));
        return new HashedPassword(encoded, HashAlgorithm.ARGON2ID);
    }

    @Override
    public boolean verify(String password, HashedPassword hashed) {
        Phc phc = Phc.parse(hashed.hash());
        if (phc == null) {
            return false;
        }
        byte[] actual = derive(password, phc.salt, phc.memory, phc.iterations, phc.parallelism, phc.hash.length);
        return MessageDigest.isEqual(actual, phc.hash);
    }

    @Override
    public boolean needsRehash(HashedPassword hashed) {
        Phc phc = Phc.parse(hashed.hash());
        return phc == null || phc.memory < MEMORY_KIB || phc.iterations < ITERATIONS;
    }

    private static byte[] derive(String password, byte[] salt, int memory, int iterations, int parallelism, int length) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[length];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }

    private record Phc(int memory, int iterations, int parallelism, byte[] salt, byte[] hash) {

        static Phc parse(String encoded) {
            // "", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash
            String[] parts = encoded.split("\\$");
            if (parts.length != 6 || !parts[1].equals("argon2id")) {
                return null;
            }
            try {
                int memory = 0, iterations = 0, parallelism = 0;
                for (String param : parts[3].split(",")) {
                    String[] kv = param.split("=", 2);
                    int value = Integer.parseInt(kv[1]);
                    switch (kv[0]) {
                        case "m" -> memory = value;
                        case "t" -> iterations = value;
                        case "p" -> parallelism = value;
                        default -> {
                            return null;
                        }
                    }
                }
                return new Phc(memory, iterations, parallelism, B64_DECODER.decode(parts[4]), B64_DECODER.decode(parts[5]));
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                return null;
            }
        }
    }
}
