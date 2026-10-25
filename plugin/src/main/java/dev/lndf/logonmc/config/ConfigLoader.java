package dev.lndf.logonmc.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Copies the bundled YAML files into the data directory on first start and loads them.
 */
public final class ConfigLoader {

    private final Path dataDirectory;

    public ConfigLoader(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public PluginConfig loadConfig() throws IOException {
        return PluginConfig.load(load("config.yml"));
    }

    public Messages loadMessages() throws IOException {
        return new Messages(load("messages.yml"), bundled("messages.yml"));
    }

    private ConfigurationNode load(String name) throws IOException {
        Path file = dataDirectory.resolve(name);
        if (Files.notExists(file)) {
            Files.createDirectories(dataDirectory);
            try (InputStream in = resource(name)) {
                Files.copy(in, file);
            }
        }
        return YamlConfigurationLoader.builder().path(file).build().load();
    }

    private ConfigurationNode bundled(String name) throws IOException {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(resource(name), StandardCharsets.UTF_8)))
                .build()
                .load();
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IOException("Missing bundled resource " + name);
        }
        return in;
    }
}
