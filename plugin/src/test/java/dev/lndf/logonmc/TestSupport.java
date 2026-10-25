package dev.lndf.logonmc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lndf.logonmc.config.PluginConfig;
import dev.lndf.logonmc.db.Database;
import dev.lndf.logonmc.db.Dialect;
import dev.lndf.logonmc.db.SchemaMigrator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public final class TestSupport {

    private TestSupport() {
    }

    public static Database h2() {
        return open(Dialect.H2, "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", 4);
    }

    public static Database sqlite(Path file) {
        return open(Dialect.SQLITE, "jdbc:sqlite:" + file, 1);
    }

    private static Database open(Dialect dialect, String url, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(dialect.driverClass());
        config.setJdbcUrl(url);
        config.setMaximumPoolSize(poolSize);
        Database database = new Database(dialect, new HikariDataSource(config));
        new SchemaMigrator(database).migrate();
        return database;
    }

    /**
     * The bundled config.yml with optional overrides applied to the raw YAML tree.
     */
    public static PluginConfig config(Consumer<ConfigurationNode> overrides) {
        try {
            ConfigurationNode root = YamlConfigurationLoader.builder()
                    .source(() -> new BufferedReader(new InputStreamReader(
                            Objects.requireNonNull(TestSupport.class.getClassLoader().getResourceAsStream("config.yml")),
                            StandardCharsets.UTF_8)))
                    .build()
                    .load();
            overrides.accept(root);
            return PluginConfig.load(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static PluginConfig config() {
        return config(root -> { });
    }
}
