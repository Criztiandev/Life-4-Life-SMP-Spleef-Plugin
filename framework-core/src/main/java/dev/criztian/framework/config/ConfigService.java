package dev.criztian.framework.config;

import dev.criztian.framework.plugin.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Typed YAML configs backed by Configurate. Config classes are annotated with
 * {@code @ConfigSerializable}; fields keep their Java defaults when absent from
 * the file, and {@code @Comment} annotations are written into the YAML.
 */
public final class ConfigService implements Service {

    private final JavaPlugin plugin;
    private final Map<String, ConfigHandle<?>> handles = new LinkedHashMap<>();

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Loads (or creates with defaults) {@code fileName} mapped to {@code type}. */
    public <T> ConfigHandle<T> config(Class<T> type, String fileName) {
        return config(type, fileName, null);
    }

    /** Same, with versioned migrations applied to existing files before mapping. */
    public synchronized <T> ConfigHandle<T> config(Class<T> type, String fileName,
                                                   @Nullable ConfigMigrations migrations) {
        ConfigHandle<?> existing = handles.get(fileName);
        if (existing != null) {
            if (existing.get().getClass() != type) {
                throw new IllegalArgumentException(fileName + " already loaded as "
                        + existing.get().getClass().getName());
            }
            @SuppressWarnings("unchecked")
            ConfigHandle<T> cast = (ConfigHandle<T>) existing;
            return cast;
        }

        Path path = dataPath(fileName);
        ConfigHandle<T> handle = new ConfigHandle<>(type, path, loaderFor(path), migrations);
        handle.reload();
        handles.put(fileName, handle);
        return handle;
    }

    /** Raw node access for files without a config class (fresh load each call). */
    public CommentedConfigurationNode rawNode(String fileName) {
        try {
            return loaderFor(dataPath(fileName)).load();
        } catch (ConfigurateException e) {
            throw new IllegalStateException("Failed to load " + fileName, e);
        }
    }

    /** Reloads every handle previously created through this service. */
    public synchronized void reloadAll() {
        handles.values().forEach(ConfigHandle::reload);
    }

    private Path dataPath(String fileName) {
        Path dataFolder = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create data folder " + dataFolder, e);
        }
        return dataFolder.resolve(fileName);
    }

    static YamlConfigurationLoader loaderFor(Path path) {
        // Comment reading/writing is on by default since Configurate 4.2.
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .build();
    }
}
