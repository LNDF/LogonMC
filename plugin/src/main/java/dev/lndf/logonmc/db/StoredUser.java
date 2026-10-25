package dev.lndf.logonmc.db;

import dev.lndf.logonmc.api.LogonUser;
import dev.lndf.logonmc.crypto.HashedPassword;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Mutable account row. Change fields, then persist with {@link UserRepository#update}.
 */
public final class StoredUser {

    private UUID uuid;
    private @Nullable UUID premiumUuid;
    private String lastNickname;
    private @Nullable HashedPassword password;
    private @Nullable Instant joined;
    private @Nullable Instant lastSeen;
    private @Nullable String lastIp;
    private @Nullable String lastServer;

    public StoredUser(UUID uuid, @Nullable UUID premiumUuid, String lastNickname, @Nullable HashedPassword password,
                      @Nullable Instant joined, @Nullable Instant lastSeen, @Nullable String lastIp,
                      @Nullable String lastServer) {
        this.uuid = uuid;
        this.premiumUuid = premiumUuid;
        this.lastNickname = lastNickname;
        this.password = password;
        this.joined = joined;
        this.lastSeen = lastSeen;
        this.lastIp = lastIp;
        this.lastServer = lastServer;
    }

    public static StoredUser create(UUID uuid, String name) {
        return new StoredUser(uuid, null, name, null, Instant.now(), null, null, null);
    }

    public UUID uuid() {
        return uuid;
    }

    void uuid(UUID uuid) {
        this.uuid = uuid;
    }

    public @Nullable UUID premiumUuid() {
        return premiumUuid;
    }

    public void premiumUuid(@Nullable UUID premiumUuid) {
        this.premiumUuid = premiumUuid;
    }

    /** Linked to a Mojang account with /premium: joins through Mojang authentication and logs in automatically. */
    public boolean isLinked() {
        return premiumUuid != null;
    }

    public String lastNickname() {
        return lastNickname;
    }

    public void lastNickname(String lastNickname) {
        this.lastNickname = lastNickname;
    }

    public @Nullable HashedPassword password() {
        return password;
    }

    public void password(@Nullable HashedPassword password) {
        this.password = password;
    }

    public boolean hasPassword() {
        return password != null;
    }

    public @Nullable Instant joined() {
        return joined;
    }

    public @Nullable Instant lastSeen() {
        return lastSeen;
    }

    public void lastSeen(@Nullable Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public @Nullable String lastIp() {
        return lastIp;
    }

    public void lastIp(@Nullable String lastIp) {
        this.lastIp = lastIp;
    }

    public @Nullable String lastServer() {
        return lastServer;
    }

    public void lastServer(@Nullable String lastServer) {
        this.lastServer = lastServer;
    }

    public LogonUser toApi() {
        return new LogonUser(uuid, premiumUuid, lastNickname, password != null, joined, lastSeen, lastServer, lastIp);
    }
}
