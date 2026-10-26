package dev.lndf.logonmc.db;

import dev.lndf.logonmc.crypto.HashedPassword;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Blocking access to logonmc_users. Names are matched case-insensitively through lowercase columns
 * so lookups behave the same on every dialect.
 *
 * <p>A cracked account owns its name: reserved_name holds it, lowercased, and is unique. A linked
 * account reserves nothing (reserved_name is NULL); last_nickname only records the name it last
 * joined with, so several linked accounts may share one after Mojang name changes.
 */
public final class UserRepository {

    private static final String COLUMNS =
            "uuid, premium_uuid, last_nickname, last_nickname_lower, reserved_name, password_hash, hash_algo, joined, last_seen, "
                    + "last_ip, last_server";

    private final Database database;

    public UserRepository(Database database) {
        this.database = database;
    }

    public Optional<StoredUser> findByUuid(UUID uuid) {
        return findOne("uuid = ?", uuid.toString());
    }

    public Optional<StoredUser> findCrackedByName(String name) {
        return findOne("reserved_name = ?", lower(name));
    }

    /**
     * Any account last seen with this name: the cracked owner if there is one, otherwise the most
     * recently seen linked account.
     */
    public Optional<StoredUser> findByName(String name) {
        return findAllByName(name).stream().findFirst();
    }

    /**
     * Every account last seen with this name, in {@link #findByName} order: the cracked owner first,
     * then linked accounts from most recently seen.
     */
    public List<StoredUser> findAllByName(String name) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT " + COLUMNS + " FROM logonmc_users "
                    + "WHERE last_nickname_lower = ? ORDER BY CASE WHEN reserved_name IS NULL THEN 1 ELSE 0 END, "
                    + "CASE WHEN last_seen IS NULL THEN 0 ELSE last_seen END DESC")) {
                ps.setString(1, lower(name));
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredUser> found = new ArrayList<>();
                    while (rs.next()) {
                        found.add(read(rs));
                    }
                    return found;
                }
            }
        });
    }

    public Optional<StoredUser> findByPremiumUuid(UUID premiumUuid) {
        return findOne("premium_uuid = ?", premiumUuid.toString());
    }

    private Optional<StoredUser> findOne(String where, String value) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT " + COLUMNS + " FROM logonmc_users WHERE " + where)) {
                ps.setString(1, value);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    public void insert(StoredUser user) {
        database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO logonmc_users (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, user.uuid().toString());
                bindFields(ps, 2, user);
                return ps.executeUpdate();
            }
        });
    }

    /** Writes every field: a stale copy would roll back later changes, such as a password reset. */
    public void update(StoredUser user) {
        database.query(connection -> update(connection, user));
    }

    public void updateMetadata(StoredUser user) {
        database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE logonmc_users SET last_seen = ?, last_ip = ?, last_server = ? WHERE uuid = ?")) {
                setInstant(ps, 1, user.lastSeen());
                setNullable(ps, 2, user.lastIp());
                setNullable(ps, 3, user.lastServer());
                ps.setString(4, user.uuid().toString());
                return ps.executeUpdate();
            }
        });
    }

    public void updateLink(StoredUser user) {
        database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE logonmc_users SET premium_uuid = ?, "
                    + "last_nickname = ?, last_nickname_lower = ?, reserved_name = ? WHERE uuid = ?")) {
                setNullable(ps, 1, user.premiumUuid() == null ? null : user.premiumUuid().toString());
                ps.setString(2, user.lastNickname());
                ps.setString(3, lower(user.lastNickname()));
                setNullable(ps, 4, user.isLinked() ? null : lower(user.lastNickname()));
                ps.setString(5, user.uuid().toString());
                return ps.executeUpdate();
            }
        });
    }

    public boolean updatePassword(UUID uuid, @Nullable HashedPassword password) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE logonmc_users SET password_hash = ?, hash_algo = ? WHERE uuid = ?")) {
                setNullable(ps, 1, password == null ? null : password.hash());
                setNullable(ps, 2, password == null ? null : password.algorithm().name());
                ps.setString(3, uuid.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** @return false if the stored password is no longer {@code expected} or the account is gone */
    public boolean replacePassword(UUID uuid, @Nullable HashedPassword expected, HashedPassword replacement) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE logonmc_users SET password_hash = ?, "
                    + "hash_algo = ? WHERE uuid = ? AND " + (expected == null ? "password_hash IS NULL" : "password_hash = ?"))) {
                ps.setString(1, replacement.hash());
                ps.setString(2, replacement.algorithm().name());
                ps.setString(3, uuid.toString());
                if (expected != null) {
                    ps.setString(4, expected.hash());
                }
                return ps.executeUpdate() > 0;
            }
        });
    }

    int update(Connection connection, StoredUser user) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE logonmc_users SET premium_uuid = ?, last_nickname = ?, last_nickname_lower = ?, reserved_name = ?, "
                        + "password_hash = ?, hash_algo = ?, joined = ?, last_seen = ?, last_ip = ?, last_server = ? WHERE uuid = ?")) {
            bindFields(ps, 1, user);
            ps.setString(11, user.uuid().toString());
            return ps.executeUpdate();
        }
    }

    /**
     * Changes the primary key of an account, moving its session along with it.
     */
    public void changeUuid(StoredUser user, UUID newUuid) {
        UUID oldUuid = user.uuid();
        database.transaction(connection -> {
            try (PreparedStatement users = connection.prepareStatement("UPDATE logonmc_users SET uuid = ? WHERE uuid = ?");
                 PreparedStatement sessions = connection.prepareStatement("UPDATE logonmc_sessions SET uuid = ? WHERE uuid = ?")) {
                users.setString(1, newUuid.toString());
                users.setString(2, oldUuid.toString());
                users.executeUpdate();
                sessions.setString(1, newUuid.toString());
                sessions.setString(2, oldUuid.toString());
                sessions.executeUpdate();
            }
            return null;
        });
        user.uuid(newUuid);
    }

    public boolean delete(UUID uuid) {
        return database.transaction(connection -> {
            try (PreparedStatement users = connection.prepareStatement("DELETE FROM logonmc_users WHERE uuid = ?");
                 PreparedStatement sessions = connection.prepareStatement("DELETE FROM logonmc_sessions WHERE uuid = ?")) {
                users.setString(1, uuid.toString());
                sessions.setString(1, uuid.toString());
                sessions.executeUpdate();
                return users.executeUpdate() > 0;
            }
        });
    }

    private static void bindFields(PreparedStatement ps, int start, StoredUser user) throws SQLException {
        HashedPassword password = user.password();
        setNullable(ps, start, user.premiumUuid() == null ? null : user.premiumUuid().toString());
        ps.setString(start + 1, user.lastNickname());
        ps.setString(start + 2, lower(user.lastNickname()));
        setNullable(ps, start + 3, user.isLinked() ? null : lower(user.lastNickname()));
        setNullable(ps, start + 4, password == null ? null : password.hash());
        setNullable(ps, start + 5, password == null ? null : password.algorithm().name());
        setInstant(ps, start + 6, user.joined());
        setInstant(ps, start + 7, user.lastSeen());
        setNullable(ps, start + 8, user.lastIp());
        setNullable(ps, start + 9, user.lastServer());
    }

    private static StoredUser read(ResultSet rs) throws SQLException {
        String premium = rs.getString("premium_uuid");
        String hash = rs.getString("password_hash");
        String algo = rs.getString("hash_algo");
        return new StoredUser(
                UUID.fromString(rs.getString("uuid")),
                premium == null ? null : UUID.fromString(premium),
                rs.getString("last_nickname"),
                hash == null ? null : HashedPassword.of(hash, algo),
                getInstant(rs, "joined"),
                getInstant(rs, "last_seen"),
                rs.getString("last_ip"),
                rs.getString("last_server")
        );
    }

    private static void setNullable(PreparedStatement ps, int index, @Nullable String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setInstant(PreparedStatement ps, int index, @Nullable Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value.toEpochMilli());
        }
    }

    private static @Nullable Instant getInstant(ResultSet rs, String column) throws SQLException {
        long millis = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(millis);
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
