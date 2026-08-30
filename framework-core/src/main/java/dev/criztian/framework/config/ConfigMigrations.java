package dev.criztian.framework.config;

import java.util.Map;
import java.util.TreeMap;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;

/**
 * Versioned config migrations. Each step upgrades the raw node tree TO the
 * given version; steps run in ascending order starting above the file's
 * current version. The version is tracked in the file under {@code versionKey}
 * (default {@code config-version}) — the config class itself must not declare
 * that field.
 *
 * <pre>{@code
 * ConfigMigrations.builder()
 *     .step(2, node -> node.node("new-section", "enabled").set(true))
 *     .build();
 * }</pre>
 */
public final class ConfigMigrations {

    @FunctionalInterface
    public interface MigrationStep {
        void apply(ConfigurationNode node) throws ConfigurateException;
    }

    private final String versionKey;
    private final ConfigurationTransformation.Versioned transformation;
    private final int latestVersion;

    private ConfigMigrations(String versionKey, ConfigurationTransformation.Versioned transformation,
                             int latestVersion) {
        this.versionKey = versionKey;
        this.transformation = transformation;
        this.latestVersion = latestVersion;
    }

    public static Builder builder() {
        return new Builder("config-version");
    }

    public static Builder builder(String versionKey) {
        return new Builder(versionKey);
    }

    String versionKey() {
        return versionKey;
    }

    int latestVersion() {
        return latestVersion;
    }

    /** Applies pending steps and updates the stored version. */
    void apply(ConfigurationNode node) throws ConfigurateException {
        transformation.apply(node);
    }

    public static final class Builder {

        private final String versionKey;
        private final Map<Integer, MigrationStep> steps = new TreeMap<>();

        private Builder(String versionKey) {
            this.versionKey = versionKey;
        }

        /** Registers the step that upgrades the config TO {@code targetVersion}. */
        public Builder step(int targetVersion, MigrationStep step) {
            steps.put(targetVersion, step);
            return this;
        }

        public ConfigMigrations build() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("ConfigMigrations needs at least one step");
            }
            var builder = ConfigurationTransformation.versionedBuilder()
                    .versionKey(versionKey);
            int latest = 0;
            for (var entry : steps.entrySet()) {
                MigrationStep step = entry.getValue();
                builder.addVersion(entry.getKey(), step::apply);
                latest = Math.max(latest, entry.getKey());
            }
            return new ConfigMigrations(versionKey, builder.build(), latest);
        }
    }
}
