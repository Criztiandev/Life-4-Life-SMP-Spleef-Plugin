package dev.criztian.framework.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** MiniMessage parsing helpers. Adventure is provided by Paper — never shaded. */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {}

    /** Parses a MiniMessage string, e.g. {@code mm("<green>Hi <name>", Placeholder.unparsed("name", n))}. */
    public static Component mm(String miniMessage, TagResolver... resolvers) {
        return MINI.deserialize(miniMessage, resolvers);
    }
}
