package dev.criztian.framework.integration.papi;

import dev.criztian.framework.integration.papi.PlaceholderService.PlaceholderResolver;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every reference to PlaceholderAPI types lives here, isolated in its own class
 * file. The JVM verifier loads a class's referenced types when that class is
 * linked, so naming {@code PlaceholderExpansion} inside {@link PlaceholderService}
 * would make the whole service fail to load on servers without PlaceholderAPI.
 * Only call into this class after confirming the plugin is installed.
 */
final class PapiExpansions {

    private PapiExpansions() {}

    /**
     * Registers an expansion.
     *
     * @return an unregister hook, or {@code null} if PlaceholderAPI rejected it
     */
    static @Nullable Runnable register(String identifier, String author, String version,
                                       PlaceholderResolver resolver) {
        PlaceholderExpansion expansion = new PlaceholderExpansion() {
            @Override
            public @NotNull String getIdentifier() {
                return identifier;
            }

            @Override
            public @NotNull String getAuthor() {
                return author;
            }

            @Override
            public @NotNull String getVersion() {
                return version;
            }

            @Override
            public boolean persist() {
                return true; // survive /papi reload — we are an internal expansion
            }

            @Override
            public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
                return resolver.resolve(player, params);
            }
        };
        return expansion.register() ? expansion::unregister : null;
    }

    /** Resolves {@code %placeholder%} for a player and converts the legacy output. */
    static Component resolve(OfflinePlayer viewer, String placeholder) {
        String parsed = PlaceholderAPI.setPlaceholders(viewer, "%" + placeholder + "%");
        return LegacyComponentSerializer.legacySection().deserialize(parsed);
    }
}
