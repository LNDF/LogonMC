package dev.lndf.logonmc.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

/**
 * Blocking access to logonmc_sessions: at most one remembered (IP, expiry) per account.
 */
public final class SessionRepository {

    private final Database database;

    public SessionRepository(Database database) {
        this.database = database;
    }

    public void save(UUID uuid, String ip, Instant expiresAt) {
        database.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM logonmc_sessions WHERE uuid = ?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO logonmc_sessions (uuid, ip, expires_at) VALUES (?, ?, ?)")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
                insert.setString(1, uuid.toString());
                insert.setString(2, ip);
                insert.setLong(3, expiresAt.toEpochMilli());
                insert.executeUpdate();
            }
            return null;
        });
    }

    public boolean isValid(UUID uuid, String ip, Instant now) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM logonmc_sessions WHERE uuid = ? AND ip = ? AND expires_at > ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ip);
                ps.setLong(3, now.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void delete(UUID uuid) {
        database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM logonmc_sessions WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    /**
     * @return the number of expired sessions removed
     */
    public int purgeExpired(Instant now) {
        return database.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM logonmc_sessions WHERE expires_at <= ?")) {
                ps.setLong(1, now.toEpochMilli());
                return ps.executeUpdate();
            }
        });
    }
}
