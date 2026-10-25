package dev.lndf.logonmc.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lndf.logonmc.config.PluginConfig;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Pooled JDBC access. All methods block; callers run them off Netty and command threads.
 */
public final class Database implements AutoCloseable {

    private final Dialect dialect;
    private final HikariDataSource dataSource;

    public Database(Dialect dialect, HikariDataSource dataSource) {
        this.dialect = dialect;
        this.dataSource = dataSource;
    }

    public static Database create(PluginConfig.Database config, Path dataDirectory) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("LogonMC");
        hikari.setDriverClassName(config.type().driverClass());
        switch (config.type()) {
            case H2 -> {
                hikari.setJdbcUrl("jdbc:h2:file:" + dataDirectory.resolve("logonmc").toAbsolutePath());
                hikari.setMaximumPoolSize(config.poolSize());
            }
            case SQLITE -> {
                hikari.setJdbcUrl("jdbc:sqlite:" + dataDirectory.resolve("logonmc.db").toAbsolutePath());
                // SQLite allows a single writer; one connection avoids SQLITE_BUSY.
                hikari.setMaximumPoolSize(1);
            }
            case MYSQL -> {
                String properties = config.properties().isBlank() ? "" : "?" + config.properties();
                hikari.setJdbcUrl("jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.name() + properties);
                hikari.setUsername(config.user());
                hikari.setPassword(config.password());
                hikari.setMaximumPoolSize(config.poolSize());
            }
        }
        return new Database(config.type(), new HikariDataSource(hikari));
    }

    public Dialect dialect() {
        return dialect;
    }

    public <T> T query(SqlFunction<T> function) {
        try (Connection connection = dataSource.getConnection()) {
            return function.apply(connection);
        } catch (SQLException e) {
            throw new DatabaseException("Database query failed", e);
        }
    }

    public <T> T transaction(SqlFunction<T> function) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = function.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database transaction failed", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
