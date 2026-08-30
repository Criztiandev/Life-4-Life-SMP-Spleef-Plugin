package dev.criztian.framework.config;

import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * A typed, reloadable view of one YAML config file. Obtained from
 * {@link ConfigService#config(Class, String)}.
 */
public final class ConfigHandle<T> {

    private final Class<T> type;
    private final Path path;
    private final YamlConfigurationLoader loader;
    private final @Nullable ConfigMigrations migrations;

    private volatile T value;

    ConfigHandle(Class<T> type, Path path, YamlConfigurationLoader loader,
                 @Nullable ConfigMigrations migrations) {
        this.type = type;
        this.path = path;
        this.loader = loader;
        this.migrations = migrations;
    }

    /** The current config values. Never null after the handle is created. */
    public T get() {
        return value;
    }

    public Path path() {
        return path;
    }

    /** Re-reads the file, applying migrations and writing back new defaults/comments. */
    public void reload() {
        try {
            CommentedConfigurationNode node = loader.load();
            // A missing OR empty file is a fresh config, not a legacy one:
            // migrating an empty tree would run every step against nothing.
            boolean hasExistingContent = !node.empty();

            if (hasExistingContent && migrations != null) {
                migrations.apply(node);
            }

            T instance = node.get(type);
            if (instance == null) {
                instance = newInstance();
            }

            // Write back so new fields and @Comment blocks appear in existing files.
            node.set(type, instance);
            stampVersion(node);
            loader.save(node);
            YamlCommentWriter.apply(path, node); // configurate 4.2 drops comments on save

            this.value = instance;
        } catch (ConfigurateException e) {
            throw new IllegalStateException("Failed to load config " + path.getFileName(), e);
        }
    }

    /** Persists the in-memory values (and any code-side mutations) to disk. */
    public void save() {
        try {
            CommentedConfigurationNode node = loader.load();
            node.set(type, value);
            stampVersion(node);
            loader.save(node);
            YamlCommentWriter.apply(path, node);
        } catch (ConfigurateException e) {
            throw new IllegalStateException("Failed to save config " + path.getFileName(), e);
        }
    }

    private void stampVersion(CommentedConfigurationNode node) throws ConfigurateException {
        if (migrations == null) {
            return;
        }
        CommentedConfigurationNode versionNode = node.node(migrations.versionKey());
        // Never lower a stored version: a file written by a NEWER plugin build
        // would otherwise be re-migrated after a downgrade/re-upgrade cycle.
        if (versionNode.getInt(-1) < migrations.latestVersion()) {
            versionNode.set(migrations.latestVersion());
        }
    }

    private T newInstance() {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Config class " + type.getName() + " needs a no-args constructor", e);
        }
    }
}
