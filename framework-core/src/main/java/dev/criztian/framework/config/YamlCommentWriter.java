package dev.criztian.framework.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Best-effort YAML comment injection. Configurate 4.2's YAML emitter drops
 * node comments on save (reading works, writing landed after 4.2), so after
 * saving we re-open the file and insert {@code # ...} lines above keys whose
 * nodes carry comments ({@code @Comment} fields, or comments read from a
 * bundled resource).
 *
 * <p>Assumes the block-style, 2-space-indent output our loaders produce and
 * plain (unquoted) keys. Nodes it cannot match are simply left uncommented.
 * Framework-internal API.</p>
 */
public final class YamlCommentWriter {

    private YamlCommentWriter() {}

    public static void apply(Path file, CommentedConfigurationNode root) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size() + 16);
            int[] cursor = {0};
            walk(root, 0, lines, cursor, out);
            for (int i = cursor[0]; i < lines.size(); i++) {
                out.add(lines.get(i));
            }
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // comments are cosmetic — never fail the save over them
        }
    }

    private static void walk(CommentedConfigurationNode node, int depth,
                             List<String> lines, int[] cursor, List<String> out) {
        if (!node.isMap()) {
            return;
        }
        String indent = "  ".repeat(depth);
        for (Map.Entry<Object, CommentedConfigurationNode> entry : node.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            CommentedConfigurationNode child = entry.getValue();

            while (cursor[0] < lines.size()) {
                String line = lines.get(cursor[0]);
                if (isKeyLine(line, indent, key)) {
                    String comment = child.comment();
                    if (comment != null && !alreadyCommented(out)) {
                        for (String commentLine : comment.split("\n", -1)) {
                            out.add(indent + "# " + commentLine.strip());
                        }
                    }
                    out.add(line);
                    cursor[0]++;
                    walk(child, depth + 1, lines, cursor, out);
                    break;
                }
                out.add(line);
                cursor[0]++;
            }
        }
    }

    private static boolean isKeyLine(String line, String indent, String key) {
        if (!line.startsWith(indent) || line.length() <= indent.length()
                || line.charAt(indent.length()) == ' ' || line.charAt(indent.length()) == '#') {
            return false;
        }
        String rest = line.substring(indent.length());
        return rest.startsWith(key) && rest.length() > key.length() && rest.charAt(key.length()) == ':';
    }

    private static boolean alreadyCommented(List<String> out) {
        return !out.isEmpty() && out.get(out.size() - 1).stripLeading().startsWith("#");
    }

    /** Convenience for callers holding a plain ConfigurationNode. */
    public static void applyIfCommented(Path file, ConfigurationNode node) {
        if (node instanceof CommentedConfigurationNode commented) {
            apply(file, commented);
        }
    }
}
