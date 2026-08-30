package dev.criztian.framework.integration.papi;

import dev.criztian.framework.plugin.Service;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI integration (soft-depend). This class deliberately names no
 * PlaceholderAPI type — all of those live in {@link PapiExpansions}, which is
 * only touched behind {@link #available()} checks — so the framework loads
 * normally on servers without PlaceholderAPI installed.
 */
public final class PlaceholderService implements Service {

    private final JavaPlugin plugin;
    /** Unregister hooks, run on disable so reloads don't leak stale resolvers. */
    private final List<Runnable> unregisterHooks = new ArrayList<>();

    public PlaceholderService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * Registers a {@code %identifier_params%} expansion. No-op (with a log
     * line) when PlaceholderAPI is not installed.
     */
    public void register(String identifier, String author, String version,
                         PlaceholderResolver resolver) {
        if (!available()) {
            plugin.getSLF4JLogger().info(
                    "PlaceholderAPI not installed — skipping %{}_...% placeholders", identifier);
            return;
        }
        Runnable unregister = PapiExpansions.register(identifier, author, version, resolver);
        if (unregister == null) {
            plugin.getSLF4JLogger().warn(
                    "PlaceholderAPI rejected the '{}' expansion — its placeholders will not resolve",
                    identifier);
            return;
        }
        unregisterHooks.add(unregister);
    }

    /**
     * MiniMessage tag resolving {@code <papi:some_placeholder>} against a
     * player. Resolves to nothing when PlaceholderAPI is absent.
     */
    public TagResolver papiTag(OfflinePlayer viewer) {
        if (!available()) {
            return TagResolver.empty();
        }
        return TagResolver.resolver("papi", (args, context) -> {
            String placeholder = args.popOr("<papi> tag needs a placeholder argument").value();
            return Tag.inserting(PapiExpansions.resolve(viewer, placeholder));
        });
    }

    @Override
    public void shutdown() {
        for (Runnable unregister : unregisterHooks) {
            try {
                unregister.run();
            } catch (Throwable ignored) {
                // PlaceholderAPI may already be disabled — nothing to clean up
            }
        }
        unregisterHooks.clear();
    }

    /** Resolves the {@code params} part of {@code %identifier_params%}; null = unknown. */
    @FunctionalInterface
    public interface PlaceholderResolver {
        @Nullable String resolve(OfflinePlayer player, String params);
    }
}
