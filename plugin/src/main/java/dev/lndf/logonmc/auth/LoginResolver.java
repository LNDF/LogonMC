package dev.lndf.logonmc.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lndf.logonmc.api.event.PremiumMigrationEvent;
import dev.lndf.logonmc.api.event.PremiumNameChangeEvent;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.db.StoredUser;
import dev.lndf.logonmc.db.UserRepository;
import dev.lndf.logonmc.mojang.MojangProfile;
import dev.lndf.logonmc.mojang.ProfileLookup;
import dev.lndf.logonmc.util.Uuids;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Decides how a connecting player authenticates and maps their login to a stored account.
 * Contains the name validation, premium detection and account linking rules; it has no Velocity
 * dependencies besides firing API events. All methods block on the database.
 *
 * <p>Rules:
 * <ul>
 *   <li>Every account is created with /register. Accounts are cracked until linked with /premium.</li>
 *   <li>A cracked account owns its name. Nobody else, premium or not, can join with it.</li>
 *   <li>A linked account is found by its Mojang UUID, logs in automatically and follows Mojang name
 *       changes. It owns no name, so a name it used before can be claimed by someone else.</li>
 *   <li>Nothing is ever renamed to resolve a conflict: the joining player is refused instead.</li>
 * </ul>
 */
public final class LoginResolver {

    public enum Mode { ONLINE, OFFLINE, DENY }

    /**
     * @param messageKey messages.yml key for DENY
     * @param correctName the name's registered capitalization, for the wrong-case message
     */
    public record Decision(Mode mode, @Nullable String messageKey, @Nullable String correctName) {
        static final Decision ONLINE = new Decision(Mode.ONLINE, null, null);
        static final Decision OFFLINE = new Decision(Mode.OFFLINE, null, null);

        static Decision deny(String messageKey) {
            return new Decision(Mode.DENY, messageKey, null);
        }

        static Decision wrongCase(String correctName) {
            return new Decision(Mode.DENY, "kick.wrong-case", correctName);
        }
    }

    /** Thrown during the profile phase when the login must be refused; carries a messages.yml key. */
    public static final class LoginDeniedException extends RuntimeException {
        private final String messageKey;

        LoginDeniedException(String messageKey, String detail) {
            super(detail);
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /** The Mojang handshake owed to a cracked account after /premium. */
    private enum Attempt { REQUESTED, IN_PROGRESS }

    private final UserRepository users;
    private final ProfileLookup mojang;
    private final Supplier<PluginConfig> config;
    private final Function<Object, CompletableFuture<?>> eventFirer;
    private final Logger logger;

    private final Cache<String, Attempt> attempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15)).build();

    public LoginResolver(UserRepository users, ProfileLookup mojang, Supplier<PluginConfig> config,
                         Function<Object, CompletableFuture<?>> eventFirer, Logger logger) {
        this.users = users;
        this.mojang = mojang;
        this.config = config;
        this.eventFirer = eventFirer;
        this.logger = logger;
    }

    /**
     * Pre-login: validate the name and choose online (Mojang) or offline authentication.
     */
    public Decision decide(String name) {
        PluginConfig cfg = config.get();
        if (!cfg.names().pattern().matcher(name).matches()) {
            return Decision.deny("kick.invalid-name");
        }
        String key = key(name);

        // 1. A cracked account owns the name: only its password opens it.
        Optional<StoredUser> owner = users.findCrackedByName(name);
        if (owner.isPresent()) {
            String registered = owner.get().lastNickname();
            if (cfg.names().caseCheck() && !registered.equals(name)) {
                return Decision.wrongCase(registered);
            }
            Attempt attempt = attempts.getIfPresent(key);
            if (attempt == Attempt.REQUESTED) {
                attempts.put(key, Attempt.IN_PROGRESS);
                return Decision.ONLINE;
            }
            if (attempt == Attempt.IN_PROGRESS) {
                // The /premium handshake never completed: it was not a premium client.
                attempts.invalidate(key);
                logger.info("Premium check for {} failed, continuing as a cracked account", name);
            }
            return Decision.OFFLINE;
        }

        // 2. Nobody here owns the name. Mojang says whether it is premium, and whose it is.
        Optional<MojangProfile> profile;
        try {
            profile = mojang.lookup(name);
        } catch (ProfileLookup.LookupException e) {
            logger.warn("Mojang lookup for {} failed: {}", name, e.getMessage());
            // A linked player can still be recognized by the name they last joined with.
            if (users.findByName(name).filter(StoredUser::isLinked).isPresent()) {
                return Decision.ONLINE;
            }
            return cfg.premium().denyOnMojangError() ? Decision.deny("kick.mojang-unavailable") : Decision.OFFLINE;
        }
        if (profile.isEmpty()) {
            return Decision.OFFLINE;
        }
        // 3. Premium name: Mojang authentication if a linked account owns it (possibly after a name
        //    change) or if premium names are protected. Otherwise a cracked player may register it.
        boolean linkedOwner = users.findByPremiumUuid(profile.get().id()).isPresent();
        if (!linkedOwner && !cfg.premium().protectPremiumNames()) {
            return Decision.OFFLINE;
        }
        if (cfg.names().caseCheck() && !profile.get().name().equals(name)) {
            return Decision.wrongCase(profile.get().name());
        }
        return Decision.ONLINE;
    }

    /**
     * Marks a cracked account to go through Mojang authentication on its next join. The account is
     * linked only if that handshake succeeds.
     */
    public void requestPremiumCheck(String name) {
        attempts.put(key(name), Attempt.REQUESTED);
    }

    /**
     * Profile phase of an online-mode login: the linked account of this Mojang account (following
     * a name change), or the cracked account being linked after /premium.
     *
     * @return empty for a premium player without a linked account, who registers like anyone else
     * @throws LoginDeniedException when a cracked account owns the name, or the Mojang account is
     *         already linked to a different account than the one requesting the link
     */
    public Optional<StoredUser> resolveOnline(String name, UUID premiumUuid) {
        boolean linkRequested = attempts.asMap().remove(key(name)) != null;
        Optional<StoredUser> owner = users.findCrackedByName(name);
        Optional<StoredUser> linked = users.findByPremiumUuid(premiumUuid);

        if (linked.isPresent()) {
            if (owner.isPresent()) {
                throw new LoginDeniedException(linkRequested ? "kick.premium-already-linked" : "kick.name-taken",
                        "Linked account " + linked.get().uuid() + " joined with " + name + ", which a cracked account owns");
            }
            StoredUser user = linked.get();
            if (!user.lastNickname().equals(name)) {
                String oldName = user.lastNickname();
                user.lastNickname(name);
                users.update(user);
                logger.info("Linked player {} changed name from {} to {}", premiumUuid, oldName, name);
                eventFirer.apply(new PremiumNameChangeEvent(user.uuid(), oldName, name)).join();
            }
            return linked;
        }
        if (owner.isPresent()) {
            if (!linkRequested) {
                throw new LoginDeniedException("kick.name-taken", "Premium player joined with " + name + ", which a cracked account owns");
            }
            return Optional.of(link(owner.get(), name, premiumUuid));
        }
        return Optional.empty();
    }

    /**
     * Profile phase of an offline-mode login: the cracked account that owns this name, if any.
     */
    public Optional<StoredUser> resolveOffline(String name) {
        return users.findCrackedByName(name);
    }

    /**
     * Profile UUID for a player without an account: the offline UUID of the name, unless an
     * account already uses it (a linked player who has since changed name).
     */
    public UUID newAccountUuid(String name) {
        UUID offline = Uuids.offline(name);
        return users.findByUuid(offline).isEmpty() ? offline : UUID.randomUUID();
    }

    /**
     * Whether a Mojang account has exactly this name and isn't linked to any account here.
     * Lookup failures count as no.
     */
    public boolean isLinkablePremiumName(String name) {
        try {
            return mojang.lookup(name)
                    .filter(profile -> profile.name().equals(name))
                    .filter(profile -> users.findByPremiumUuid(profile.id()).isEmpty())
                    .isPresent();
        } catch (ProfileLookup.LookupException e) {
            return false;
        }
    }

    /**
     * Links a cracked account to a Mojang account, switching its profile UUID if configured.
     * The caller has checked that the Mojang account isn't linked to another account.
     */
    public StoredUser link(StoredUser user, String name, UUID premiumUuid) {
        user.lastNickname(name);
        user.premiumUuid(premiumUuid);
        users.update(user);
        logger.info("Linked account {} to premium UUID {}", name, premiumUuid);
        if (config.get().premium().switchUuidOnPremium()) {
            switchUuid(user, premiumUuid);
        }
        return user;
    }

    private void switchUuid(StoredUser user, UUID premiumUuid) {
        UUID oldUuid = user.uuid();
        if (oldUuid.equals(premiumUuid)) {
            return;
        }
        if (users.findByUuid(premiumUuid).isPresent()) {
            logger.warn("Not switching {} to UUID {}: another account already uses it", user.lastNickname(), premiumUuid);
            return;
        }
        PremiumMigrationEvent event = new PremiumMigrationEvent(user.lastNickname(), oldUuid, premiumUuid);
        eventFirer.apply(event).join();
        if (!event.getResult().isAllowed()) {
            logger.info("A plugin kept {} on UUID {}", user.lastNickname(), oldUuid);
            return;
        }
        users.changeUuid(user, premiumUuid);
        logger.info("Switched {} from UUID {} to premium UUID {}", user.lastNickname(), oldUuid, premiumUuid);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
