package dev.lndf.logonmc.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lndf.logonmc.TestSupport;
import dev.lndf.logonmc.crypto.HashAlgorithm;
import dev.lndf.logonmc.crypto.HashedPassword;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RepositoryTest {

    @TempDir
    static Path tempDir;

    static Stream<Arguments> databases() {
        return Stream.of(
                Arguments.of("H2", (Function<Path, Database>) dir -> TestSupport.h2()),
                Arguments.of("SQLite", (Function<Path, Database>) dir -> TestSupport.sqlite(dir.resolve(UUID.randomUUID() + ".db")))
        );
    }

    private static Database open(Function<Path, Database> factory) {
        return factory.apply(tempDir);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void insertAndFindCaseInsensitive(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            UserRepository users = new UserRepository(db);
            StoredUser user = StoredUser.create(UUID.randomUUID(), "Steve");
            user.password(new HashedPassword("$argon2id$x", HashAlgorithm.ARGON2ID));
            users.insert(user);

            StoredUser found = users.findByName("sTEVE").orElseThrow();
            assertEquals("Steve", found.lastNickname());
            assertEquals(user.uuid(), found.uuid());
            assertEquals(HashAlgorithm.ARGON2ID, found.password().algorithm());
            assertTrue(users.findByUuid(user.uuid()).isPresent());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void namesAreUniqueIgnoringCase(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            UserRepository users = new UserRepository(db);
            users.insert(StoredUser.create(UUID.randomUUID(), "Alex"));
            assertThrows(DatabaseException.class, () -> users.insert(StoredUser.create(UUID.randomUUID(), "ALEX")));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void linkedAccountsReserveNoName(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            UserRepository users = new UserRepository(db);
            StoredUser first = StoredUser.create(UUID.randomUUID(), "Alex");
            first.premiumUuid(UUID.randomUUID());
            first.lastSeen(Instant.ofEpochMilli(1000));
            users.insert(first);
            StoredUser second = StoredUser.create(UUID.randomUUID(), "alex");
            second.premiumUuid(UUID.randomUUID());
            second.lastSeen(Instant.ofEpochMilli(2000));
            users.insert(second);
            assertTrue(users.findCrackedByName("Alex").isEmpty());
            assertEquals(second.uuid(), users.findByName("ALEX").orElseThrow().uuid());

            StoredUser cracked = StoredUser.create(UUID.randomUUID(), "Alex");
            users.insert(cracked);
            assertEquals(cracked.uuid(), users.findCrackedByName("alex").orElseThrow().uuid());
            assertEquals(cracked.uuid(), users.findByName("alex").orElseThrow().uuid());
            assertEquals(List.of(cracked.uuid(), second.uuid(), first.uuid()),
                    users.findAllByName("Alex").stream().map(StoredUser::uuid).toList());

            // Unlinking would make a second cracked owner of the name.
            second.premiumUuid(null);
            assertThrows(DatabaseException.class, () -> users.update(second));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void premiumUuidLookupAndMultipleNulls(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            UserRepository users = new UserRepository(db);
            users.insert(StoredUser.create(UUID.randomUUID(), "Cracked1"));
            users.insert(StoredUser.create(UUID.randomUUID(), "Cracked2"));
            UUID premium = UUID.randomUUID();
            StoredUser p = StoredUser.create(UUID.randomUUID(), "Premium");
            p.premiumUuid(premium);
            users.insert(p);
            assertEquals("Premium", users.findByPremiumUuid(premium).orElseThrow().lastNickname());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void changeUuidMovesSession(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            UserRepository users = new UserRepository(db);
            SessionRepository sessions = new SessionRepository(db);
            StoredUser user = StoredUser.create(UUID.randomUUID(), "Mover");
            users.insert(user);
            Instant now = Instant.now();
            sessions.save(user.uuid(), "1.2.3.4", now.plus(Duration.ofHours(1)));

            UUID newUuid = UUID.randomUUID();
            users.changeUuid(user, newUuid);

            assertEquals(newUuid, user.uuid());
            assertTrue(users.findByUuid(newUuid).isPresent());
            assertTrue(sessions.isValid(newUuid, "1.2.3.4", now));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void sessionsExpireAndMatchIp(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            SessionRepository sessions = new SessionRepository(db);
            UUID uuid = UUID.randomUUID();
            Instant now = Instant.now();
            sessions.save(uuid, "10.0.0.1", now.plus(Duration.ofMinutes(5)));

            assertTrue(sessions.isValid(uuid, "10.0.0.1", now));
            assertFalse(sessions.isValid(uuid, "10.0.0.2", now));
            assertFalse(sessions.isValid(uuid, "10.0.0.1", now.plus(Duration.ofMinutes(6))));

            sessions.save(uuid, "10.0.0.2", now.plus(Duration.ofMinutes(5)));
            assertFalse(sessions.isValid(uuid, "10.0.0.1", now));
            assertEquals(1, sessions.purgeExpired(now.plus(Duration.ofMinutes(10))));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void deleteRemovesUserAndSession(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            UserRepository users = new UserRepository(db);
            SessionRepository sessions = new SessionRepository(db);
            StoredUser user = StoredUser.create(UUID.randomUUID(), "Gone");
            users.insert(user);
            sessions.save(user.uuid(), "1.1.1.1", Instant.now().plus(Duration.ofHours(1)));

            assertTrue(users.delete(user.uuid()));
            assertFalse(users.delete(user.uuid()));
            assertTrue(users.findByName("Gone").isEmpty());
            assertFalse(sessions.isValid(user.uuid(), "1.1.1.1", Instant.now()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void migratorIsIdempotent(String name, Function<Path, Database> factory) {
        try (Database db = open(factory)) {
            assertEquals(0, new SchemaMigrator(db).migrate());
        }
    }
}
