package dev.lndf.logonmc.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Applies numbered schema migrations and records the current version in logonmc_schema_version.
 * The SQL sticks to types all three dialects share; timestamps are epoch milliseconds.
 */
public final class SchemaMigrator {

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE logonmc_users (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        premium_uuid VARCHAR(36) NULL,
                        last_nickname VARCHAR(32) NOT NULL,
                        last_nickname_lower VARCHAR(32) NOT NULL,
                        reserved_name VARCHAR(32) NULL,
                        password_hash VARCHAR(255) NULL,
                        hash_algo VARCHAR(16) NULL,
                        joined BIGINT NULL,
                        last_seen BIGINT NULL,
                        last_ip VARCHAR(64) NULL,
                        last_server VARCHAR(64) NULL
                    )""",
                    "CREATE UNIQUE INDEX logonmc_users_premium ON logonmc_users (premium_uuid)",
                    // Only cracked accounts own their name; linked accounts are identified by premium_uuid.
                    "CREATE UNIQUE INDEX logonmc_users_reserved ON logonmc_users (reserved_name)",
                    "CREATE INDEX logonmc_users_name ON logonmc_users (last_nickname_lower)",
                    """
                    CREATE TABLE logonmc_sessions (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        ip VARCHAR(64) NOT NULL,
                        expires_at BIGINT NOT NULL
                    )"""
            )
    );

    private final Database database;

    public SchemaMigrator(Database database) {
        this.database = database;
    }

    /**
     * @return the number of migrations applied
     */
    public int migrate() {
        return database.query(connection -> {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS logonmc_schema_version (version INT NOT NULL)");
            }
            int current = currentVersion(connection);
            int applied = 0;
            for (int version = current + 1; version <= MIGRATIONS.size(); version++) {
                apply(connection, version);
                applied++;
            }
            return applied;
        });
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM logonmc_schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void apply(Connection connection, int version) throws SQLException {
        // MySQL commits DDL implicitly, so a failed migration may be partially applied there.
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            for (String sql : MIGRATIONS.get(version - 1)) {
                st.executeUpdate(sql);
            }
            st.executeUpdate("INSERT INTO logonmc_schema_version (version) VALUES (" + version + ")");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
