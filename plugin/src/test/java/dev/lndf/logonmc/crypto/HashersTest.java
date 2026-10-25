package dev.lndf.logonmc.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HashersTest {

    @Test
    void argon2RoundTrip() {
        Argon2Hasher hasher = new Argon2Hasher();
        HashedPassword hash = hasher.hash("hunter2!€");
        assertTrue(hash.hash().startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(hasher.verify("hunter2!€", hash));
        assertFalse(hasher.verify("hunter3!€", hash));
        assertFalse(hasher.needsRehash(hash));
    }

    @Test
    void argon2UsesRandomSalt() {
        Argon2Hasher hasher = new Argon2Hasher();
        assertNotEquals(hasher.hash("same").hash(), hasher.hash("same").hash());
    }

    @Test
    void argon2RejectsMalformedHash() {
        assertFalse(new Argon2Hasher().verify("x", new HashedPassword("$argon2id$garbage", HashAlgorithm.ARGON2ID)));
    }

    @Test
    void bcryptRoundTrip() {
        BCryptHasher hasher = new BCryptHasher();
        HashedPassword hash = hasher.hash("correct horse");
        assertTrue(hasher.verify("correct horse", hash));
        assertFalse(hasher.verify("wrong horse", hash));
    }

    @Test
    void bcryptFlagsLowerCostForRehash() {
        String cost10 = at.favre.lib.crypto.bcrypt.BCrypt.with(at.favre.lib.crypto.bcrypt.BCrypt.Version.VERSION_2A)
                .hashToString(10, "password".toCharArray());
        HashedPassword hash = new HashedPassword(cost10, HashAlgorithm.BCRYPT);
        BCryptHasher hasher = new BCryptHasher();
        assertTrue(hasher.verify("password", hash));
        assertTrue(hasher.needsRehash(hash));
    }

    @Test
    void registryUpgradesOtherAlgorithms() {
        Hashers hashers = new Hashers(HashAlgorithm.ARGON2ID);
        HashedPassword bcrypt = new BCryptHasher().hash("pw123456");
        assertTrue(hashers.verify("pw123456", bcrypt));
        assertTrue(hashers.needsRehash(bcrypt));

        HashedPassword argon = hashers.hash("pw123456");
        assertEquals(HashAlgorithm.ARGON2ID, argon.algorithm());
        assertFalse(hashers.needsRehash(argon));
    }
}
