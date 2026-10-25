package dev.lndf.logonmc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.ResultedEvent;
import dev.lndf.logonmc.api.event.PremiumMigrationEvent;
import dev.lndf.logonmc.api.event.PremiumNameChangeEvent;
import dev.lndf.logonmc.TestSupport;
import dev.lndf.logonmc.auth.LoginResolver.Decision;
import dev.lndf.logonmc.auth.LoginResolver.Mode;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.crypto.HashAlgorithm;
import dev.lndf.logonmc.crypto.HashedPassword;
import dev.lndf.logonmc.db.Database;
import dev.lndf.logonmc.db.SessionRepository;
import dev.lndf.logonmc.db.StoredUser;
import dev.lndf.logonmc.db.UserRepository;
import dev.lndf.logonmc.mojang.MojangProfile;
import dev.lndf.logonmc.mojang.ProfileLookup;
import dev.lndf.logonmc.util.Uuids;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

class LoginResolverTest {

    private static final InetAddress CLIENT = InetAddress.getLoopbackAddress();
    private static final UUID NOTCH_PREMIUM = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private Database db;
    private UserRepository users;
    private SessionRepository sessions;
    private final FakeMojang mojang = new FakeMojang();
    private final List<Object> events = new ArrayList<>();
    private Consumer<Object> eventHandler = e -> { };
    private PluginConfig config;
    private LoginResolver resolver;

    @BeforeEach
    void setUp() {
        db = TestSupport.h2();
        users = new UserRepository(db);
        sessions = new SessionRepository(db);
        config = TestSupport.config();
        resolver = new LoginResolver(users, mojang, () -> config, event -> {
            events.add(event);
            eventHandler.accept(event);
            return CompletableFuture.completedFuture(event);
        }, NOPLogger.NOP_LOGGER);
        mojang.add("Notch", NOTCH_PREMIUM);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void configure(Consumer<ConfigurationNode> overrides) {
        config = TestSupport.config(overrides);
    }

    private static void set(ConfigurationNode root, Object value, Object... path) {
        try {
            root.node(path).set(value);
        } catch (SerializationException e) {
            throw new IllegalStateException(e);
        }
    }

    private StoredUser cracked(String name) {
        StoredUser user = StoredUser.create(Uuids.offline(name), name);
        user.password(new HashedPassword("$argon2id$x", HashAlgorithm.ARGON2ID));
        users.insert(user);
        return user;
    }

    private StoredUser linked(String name, UUID premiumUuid) {
        StoredUser user = cracked(name);
        user.premiumUuid(premiumUuid);
        users.update(user);
        return user;
    }

    private void unprotected() {
        configure(root -> set(root, false, "premium", "protect-premium-names"));
    }

    // ---- Name validation ----

    @Test
    void rejectsInvalidNames() {
        assertEquals(Mode.DENY, resolver.decide("ab", CLIENT).mode());
        assertEquals(Mode.DENY, resolver.decide("this_name_is_too_long", CLIENT).mode());
        assertEquals("kick.invalid-name", resolver.decide("bad-name", CLIENT).messageKey());
    }

    @Test
    void rejectsWrongCaseOfRegisteredName() {
        cracked("Steve");
        Decision decision = resolver.decide("steve", CLIENT);
        assertEquals(Mode.DENY, decision.mode());
        assertEquals("kick.wrong-case", decision.messageKey());
        assertEquals("Steve", decision.correctName());
        assertEquals(Mode.OFFLINE, resolver.decide("Steve", CLIENT).mode());
    }

    @Test
    void rejectsWrongCaseOfProtectedPremiumName() {
        Decision decision = resolver.decide("notch", CLIENT);
        assertEquals(Mode.DENY, decision.mode());
        assertEquals("Notch", decision.correctName());
    }

    @Test
    void unprotectedPremiumNameHasNoCaseCheck() {
        unprotected();
        assertEquals(Mode.OFFLINE, resolver.decide("notch", CLIENT).mode());
    }

    @Test
    void caseCheckCanBeDisabled() {
        configure(root -> set(root, false, "names", "case-check"));
        cracked("Steve");
        assertEquals(Mode.OFFLINE, resolver.decide("steve", CLIENT).mode());
    }

    // ---- Pre-login decision ----

    @Test
    void unknownNamesJoinOffline() {
        assertEquals(Mode.OFFLINE, resolver.decide("Nobody123", CLIENT).mode());
    }

    @Test
    void protectedPremiumNameNeedsMojangToRegister() {
        assertEquals(Mode.ONLINE, resolver.decide("Notch", CLIENT).mode());
    }

    @Test
    void unprotectedPremiumNameCanBeRegisteredCracked() {
        unprotected();
        assertEquals(Mode.OFFLINE, resolver.decide("Notch", CLIENT).mode());
    }

    @Test
    void crackedAccountKeepsPremiumName() {
        cracked("Notch");
        assertEquals(Mode.OFFLINE, resolver.decide("Notch", CLIENT).mode());
        assertEquals(Mode.OFFLINE, resolver.decide("Notch", CLIENT).mode());
    }

    @Test
    void linkedAccountJoinsOnlineEvenUnprotected() {
        unprotected();
        linked("Notch", NOTCH_PREMIUM);
        assertEquals(Mode.ONLINE, resolver.decide("Notch", CLIENT).mode());
    }

    @Test
    void linkedPlayerIsRecognizedAfterNameChange() {
        unprotected();
        linked("Notch", NOTCH_PREMIUM);
        mojang.add("Notch2", NOTCH_PREMIUM);
        assertEquals(Mode.ONLINE, resolver.decide("Notch2", CLIENT).mode());
        // The old name no longer exists on Mojang, so anyone may register it.
        assertEquals(Mode.OFFLINE, resolver.decide("Notch", CLIENT).mode());
    }

    @Test
    void staleLinkedNameDoesNotProtectNewOwner() {
        unprotected();
        linked("Notch", NOTCH_PREMIUM);
        mojang.add("Notch2", NOTCH_PREMIUM);
        mojang.add("Notch", UUID.randomUUID());
        assertEquals(Mode.OFFLINE, resolver.decide("Notch", CLIENT).mode());
    }

    @Test
    void mojangOutage() {
        mojang.failing = true;
        assertEquals("kick.mojang-unavailable", resolver.decide("Someone", CLIENT).messageKey());
        linked("Linked_one", UUID.randomUUID());
        assertEquals(Mode.ONLINE, resolver.decide("Linked_one", CLIENT).mode());
        configure(root -> set(root, false, "premium", "deny-on-mojang-error"));
        assertEquals(Mode.OFFLINE, resolver.decide("Someone", CLIENT).mode());
    }

    @Test
    void lookupLimitPerIp() throws Exception {
        configure(root -> set(root, 2, "premium", "lookup-limit", "max-lookups-per-ip"));
        InetAddress other = InetAddress.getByName("10.0.0.1");
        assertEquals(Mode.OFFLINE, resolver.decide("Name_one", CLIENT).mode());
        assertEquals(Mode.ONLINE, resolver.decide("Notch", CLIENT).mode());
        assertEquals("kick.lookup-limit", resolver.decide("Name_two", CLIENT).messageKey());
        // Even with deny-on-mojang-error off, so a client can't fail its own lookups to join cracked.
        configure(root -> {
            set(root, 2, "premium", "lookup-limit", "max-lookups-per-ip");
            set(root, false, "premium", "deny-on-mojang-error");
        });
        assertEquals("kick.lookup-limit", resolver.decide("Name_two", CLIENT).messageKey());
        assertEquals(Mode.OFFLINE, resolver.decide("Name_two", other).mode());
        // Registered names need no lookup.
        cracked("Steve");
        assertEquals(Mode.OFFLINE, resolver.decide("Steve", CLIENT).mode());
    }

    @Test
    void registrationRechecksPremiumNames() {
        assertEquals("auth.premium-name", resolver.offlineRegistrationRefusal("Notch"));
        assertNull(resolver.offlineRegistrationRefusal("Someone"));
        mojang.failing = true;
        assertEquals("auth.mojang-unavailable", resolver.offlineRegistrationRefusal("Other"));
        configure(root -> set(root, false, "premium", "deny-on-mojang-error"));
        assertNull(resolver.offlineRegistrationRefusal("Other"));
        unprotected();
        mojang.failing = false;
        assertNull(resolver.offlineRegistrationRefusal("Notch"));
    }

    // ---- Account resolution ----

    @Test
    void premiumPlayerWithoutLinkedAccountMustRegister() {
        assertTrue(resolver.resolveOnline("Notch", NOTCH_PREMIUM).isEmpty());
        assertTrue(users.findByName("Notch").isEmpty());
        assertEquals(Uuids.offline("Notch"), resolver.newAccountUuid("Notch"));
    }

    @Test
    void newAccountAvoidsUuidOfRenamedLinkedAccount() {
        StoredUser old = linked("Notch", NOTCH_PREMIUM);
        old.lastNickname("Notch2");
        users.update(old);
        assertNotEquals(Uuids.offline("Notch"), resolver.newAccountUuid("Notch"));
    }

    @Test
    void offlineJoinFindsOnlyCrackedOwner() {
        StoredUser owner = cracked("Steve");
        linked("Notch", NOTCH_PREMIUM);
        assertEquals(owner.uuid(), resolver.resolveOffline("Steve").orElseThrow().uuid());
        assertTrue(resolver.resolveOffline("Notch").isEmpty());
    }

    @Test
    void linkedAccountIsFoundByPremiumUuid() {
        StoredUser user = linked("Notch", NOTCH_PREMIUM);
        assertEquals(user.uuid(), resolver.resolveOnline("Notch", NOTCH_PREMIUM).orElseThrow().uuid());
        assertTrue(events.isEmpty());
    }

    @Test
    void premiumRequestLinksAccountAndKeepsUuid() {
        StoredUser original = cracked("Notch");
        resolver.requestPremiumCheck("Notch");

        assertEquals(Mode.ONLINE, resolver.decide("Notch", CLIENT).mode());
        StoredUser linked = resolver.resolveOnline("Notch", NOTCH_PREMIUM).orElseThrow();

        assertEquals(original.uuid(), linked.uuid());
        assertEquals(NOTCH_PREMIUM, linked.premiumUuid());
        assertTrue(linked.hasPassword());
        assertTrue(users.findCrackedByName("Notch").isEmpty());
        assertEquals(Mode.ONLINE, resolver.decide("Notch", CLIENT).mode());
        assertTrue(events.isEmpty());
    }

    @Test
    void premiumRequestCanSwitchUuid() {
        configure(root -> set(root, true, "premium", "switch-uuid-on-premium"));
        StoredUser original = cracked("Notch");
        sessions.save(original.uuid(), "1.2.3.4", Instant.now().plus(Duration.ofHours(1)));
        resolver.requestPremiumCheck("Notch");
        resolver.decide("Notch", CLIENT);

        StoredUser linked = resolver.resolveOnline("Notch", NOTCH_PREMIUM).orElseThrow();

        assertEquals(NOTCH_PREMIUM, linked.uuid());
        assertTrue(users.findByUuid(original.uuid()).isEmpty());
        PremiumMigrationEvent event = (PremiumMigrationEvent) events.getFirst();
        assertEquals(original.uuid(), event.getOldUuid());
        assertEquals(NOTCH_PREMIUM, event.getNewUuid());
    }

    @Test
    void cancelledMigrationKeepsUuidButLinks() {
        configure(root -> set(root, true, "premium", "switch-uuid-on-premium"));
        eventHandler = e -> {
            if (e instanceof PremiumMigrationEvent migration) {
                migration.setResult(ResultedEvent.GenericResult.denied());
            }
        };
        StoredUser original = cracked("Notch");
        resolver.requestPremiumCheck("Notch");
        resolver.decide("Notch", CLIENT);

        StoredUser linked = resolver.resolveOnline("Notch", NOTCH_PREMIUM).orElseThrow();
        assertEquals(original.uuid(), linked.uuid());
        assertEquals(NOTCH_PREMIUM, linked.premiumUuid());
    }

    @Test
    void failedPremiumHandshakeFallsBackToCracked() {
        cracked("Notch");
        resolver.requestPremiumCheck("Notch");
        assertEquals(Mode.ONLINE, resolver.decide("Notch", CLIENT).mode());
        // No resolveOnline: the client could not complete Mojang authentication.
        assertEquals(Mode.OFFLINE, resolver.decide("Notch", CLIENT).mode());
        assertNull(users.findByName("Notch").orElseThrow().premiumUuid());
    }

    @Test
    void premiumPlayerCannotTakeCrackedName() {
        StoredUser owner = cracked("Notch");
        LoginResolver.LoginDeniedException e = assertThrows(LoginResolver.LoginDeniedException.class,
                () -> resolver.resolveOnline("Notch", NOTCH_PREMIUM));
        assertEquals("kick.name-taken", e.messageKey());
        assertNull(users.findByUuid(owner.uuid()).orElseThrow().premiumUuid());
    }

    @Test
    void linkedPlayerCannotRenameOntoCrackedName() {
        StoredUser user = linked("Notch", NOTCH_PREMIUM);
        cracked("Jeb_");
        LoginResolver.LoginDeniedException e = assertThrows(LoginResolver.LoginDeniedException.class,
                () -> resolver.resolveOnline("Jeb_", NOTCH_PREMIUM));
        assertEquals("kick.name-taken", e.messageKey());
        assertEquals("Notch", users.findByUuid(user.uuid()).orElseThrow().lastNickname());
    }

    @Test
    void linkRefusedWhenPremiumAccountLinkedElsewhere() {
        linked("Alt_account", NOTCH_PREMIUM);
        StoredUser owner = cracked("Notch");
        resolver.requestPremiumCheck("Notch");
        resolver.decide("Notch", CLIENT);
        LoginResolver.LoginDeniedException e = assertThrows(LoginResolver.LoginDeniedException.class,
                () -> resolver.resolveOnline("Notch", NOTCH_PREMIUM));
        assertEquals("kick.premium-already-linked", e.messageKey());
        assertNull(users.findByUuid(owner.uuid()).orElseThrow().premiumUuid());
    }

    @Test
    void linkedNameChangeIsFollowedWithoutTouchingOthers() {
        StoredUser user = linked("Notch", NOTCH_PREMIUM);
        mojang.add("Notch2", NOTCH_PREMIUM);

        StoredUser renamed = resolver.resolveOnline("Notch2", NOTCH_PREMIUM).orElseThrow();

        assertEquals(user.uuid(), renamed.uuid());
        assertEquals("Notch2", users.findByUuid(user.uuid()).orElseThrow().lastNickname());
        PremiumNameChangeEvent event = (PremiumNameChangeEvent) events.getFirst();
        assertEquals("Notch", event.getOldName());
        assertEquals("Notch2", event.getNewName());
    }

    @Test
    void newOwnerOfStaleLinkedNameRegistersAlongside() {
        StoredUser stale = linked("Notch", NOTCH_PREMIUM);
        UUID newOwner = UUID.randomUUID();
        mojang.add("Notch2", NOTCH_PREMIUM);
        mojang.add("Notch", newOwner);

        assertEquals(Mode.ONLINE, resolver.decide("Notch", CLIENT).mode());
        assertTrue(resolver.resolveOnline("Notch", newOwner).isEmpty());
        // They register: a cracked account named Notch next to the stale linked one, nobody renamed.
        UUID uuid = resolver.newAccountUuid("Notch");
        assertNotEquals(stale.uuid(), uuid);
        users.insert(StoredUser.create(uuid, "Notch"));

        assertEquals(uuid, users.findCrackedByName("Notch").orElseThrow().uuid());
        assertEquals(uuid, users.findByName("Notch").orElseThrow().uuid());
        assertEquals("Notch", users.findByUuid(stale.uuid()).orElseThrow().lastNickname());
        assertEquals(Mode.OFFLINE, resolver.decide("Notch", CLIENT).mode());
    }

    @Test
    void linkablePremiumName() {
        assertTrue(resolver.isLinkablePremiumName("Notch"));
        assertFalse(resolver.isLinkablePremiumName("notch"));
        assertFalse(resolver.isLinkablePremiumName("Nobody_here"));
        linked("Notch", NOTCH_PREMIUM);
        assertFalse(resolver.isLinkablePremiumName("Notch"));
    }

    private static final class FakeMojang implements ProfileLookup {
        private final Map<String, MojangProfile> profiles = new HashMap<>();
        boolean failing;

        void add(String name, UUID id) {
            profiles.values().removeIf(p -> p.id().equals(id));
            profiles.put(name.toLowerCase(Locale.ROOT), new MojangProfile(id, name));
        }

        @Override
        public Optional<MojangProfile> lookup(String name) throws LookupException {
            if (failing) {
                throw new LookupException("down", null);
            }
            return Optional.ofNullable(profiles.get(name.toLowerCase(Locale.ROOT)));
        }
    }
}
