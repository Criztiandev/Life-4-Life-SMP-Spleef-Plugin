package dev.criztian.framework.message;

import dev.criztian.framework.plugin.Service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.java.JavaPlugin;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Keyed MiniMessage messages from {@code messages.yml}. Missing keys are merged
 * in from the plugin jar's bundled {@code messages.yml} on every load, so new
 * messages appear in existing installs. Nested YAML sections become dotted keys
 * ({@code errors.no-permission}).
 *
 * <p>The {@code prefix} key is special: every message can reference it with the
 * {@code <prefix>} tag.</p>
 */
public final class MessageService implements Service {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Messages and their prefix resolver, published as one unit. */
    private record Snapshot(Map<String, String> messages, TagResolver prefix) {}

    private final JavaPlugin plugin;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), TagResolver.empty());

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        reload();
    }

    /** Looks up and parses a message; unknown keys render as a visible error. */
    public Component msg(String key, TagResolver... resolvers) {
        Snapshot current = snapshot;
        String raw = current.messages().get(key);
        if (raw == null) {
            return Component.text("Missing message: " + key, NamedTextColor.RED);
        }
        return parse(current, raw, resolvers);
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(msg(key, resolvers));
    }

    /** Parses raw MiniMessage with the {@code <prefix>} tag available. */
    public Component parse(String miniMessage, TagResolver... resolvers) {
        return parse(snapshot, miniMessage, resolvers);
    }

    private static Component parse(Snapshot snapshot, String miniMessage, TagResolver... resolvers) {
        return MINI.deserialize(miniMessage,
                TagResolver.resolver(snapshot.prefix(), TagResolver.resolver(resolvers)));
    }

    public void reload() {
        Path file = plugin.getDataFolder().toPath().resolve("messages.yml");
        try {
            Files.createDirectories(file.getParent());

            YamlConfigurationLoader fileLoader = YamlConfigurationLoader.builder()
                    .path(file)
                    .nodeStyle(NodeStyle.BLOCK)
                    .indent(2)
                    .build();
            CommentedConfigurationNode node = fileLoader.load();

            CommentedConfigurationNode defaults = loadBundledDefaults();
            if (defaults != null) {
                node.mergeFrom(defaults); // adds only missing keys
            }
            fileLoader.save(node);
            // preserve comments carried over from the bundled resource
            dev.criztian.framework.config.YamlCommentWriter.apply(file, node);

            Map<String, String> flat = new HashMap<>();
            flatten(node, "", flat);

            String prefixRaw = flat.get("prefix");
            TagResolver prefix = prefixRaw == null
                    ? TagResolver.empty()
                    : Placeholder.component("prefix", MINI.deserialize(prefixRaw));

            // Single publish: readers never see new messages with an old prefix.
            this.snapshot = new Snapshot(Map.copyOf(flat), prefix);
        } catch (IOException e) { // ConfigurateException extends IOException
            throw new IllegalStateException("Failed to load messages.yml", e);
        }
    }

    private CommentedConfigurationNode loadBundledDefaults() throws ConfigurateException {
        InputStream resource = plugin.getResource("messages.yml");
        if (resource == null) {
            return null;
        }
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8)))
                .build()
                .load();
    }

    private static void flatten(ConfigurationNode node, String prefix, Map<String, String> out) {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                String key = prefix.isEmpty()
                        ? String.valueOf(entry.getKey())
                        : prefix + "." + entry.getKey();
                flatten(entry.getValue(), key, out);
            }
        } else {
            String value = node.getString();
            if (value != null) {
                out.put(prefix, value);
            }
        }
    }
}
