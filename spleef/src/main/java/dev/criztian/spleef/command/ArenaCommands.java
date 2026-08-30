package dev.criztian.spleef.command;

import dev.criztian.spleef.SpleefPlugin;
import dev.criztian.spleef.arena.Arena;
import dev.criztian.spleef.arena.ArenaStore;
import dev.criztian.spleef.arena.Cuboid;
import dev.criztian.spleef.arena.wand.Selection;
import dev.criztian.spleef.scatter.Scatter;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.paper.util.sender.PlayerSource;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.Nullable;

/** Wand and arena management. */
public final class ArenaCommands {

    private final SpleefPlugin plugin;

    public ArenaCommands(SpleefPlugin plugin) {
        this.plugin = plugin;
    }

    @Command("spleef wand")
    @CommandDescription("Get the arena selection wand")
    @Permission("spleef.admin")
    public void wand(PlayerSource source) {
        Player player = source.source();
        player.getInventory().addItem(plugin.wand().create(plugin.wandMaterial()));
        plugin.messages().send(player, "wand.given");
    }

    @Command("spleef area save <name>")
    @CommandDescription("Snapshot the wand selection and save it as a named arena")
    @Permission("spleef.admin")
    public void save(PlayerSource source, @Argument("name") String name) {
        Player player = source.source();
        if (!ArenaStore.validName(name)) {
            plugin.messages().send(player, "area.bad-name");
            return;
        }
        Selection selection = plugin.selections().peek(player.getUniqueId());
        if (selection == null || !selection.complete()) {
            plugin.messages().send(player, "area.no-selection");
            return;
        }
        if (plugin.arenas().busy()) {
            plugin.messages().send(player, "busy");
            return;
        }

        Cuboid region;
        try {
            region = selection.toCuboid();
        } catch (RuntimeException e) {
            plugin.messages().send(player, "area.bad-selection",
                    Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            return;
        }

        plugin.messages().send(player, "area.saving",
                Placeholder.unparsed("name", name),
                Placeholder.unparsed("blocks", String.valueOf(region.volume())));

        plugin.arenas().capture(name, region).whenComplete((arena, error) ->
                plugin.scheduler().run(() -> {
                    if (error != null) {
                        fail(player, "area.save-failed", error);
                        return;
                    }
                    plugin.messages().send(player, "area.saved",
                            Placeholder.unparsed("name", arena.name()),
                            Placeholder.unparsed("blocks", String.valueOf(region.volume())),
                            Placeholder.unparsed("size",
                                    kib(plugin.arenaStore().snapshotBytes(arena.name()))));
                    if (arena.hasBlockEntities()) {
                        plugin.messages().send(player, "area.block-entity-warning",
                                Placeholder.unparsed("count", String.valueOf(arena.blockEntities())));
                    }
                    if (plugin.config().activeArena.isBlank()) {
                        plugin.activeArena(arena.name());
                        plugin.messages().send(player, "area.now-active",
                                Placeholder.unparsed("name", arena.name()));
                    }
                }));
    }

    @Command("spleef area resnapshot [name]")
    @CommandDescription("Re-capture an existing arena in place after editing the build")
    @Permission("spleef.admin")
    public void resnapshot(Source source, @Argument(value = "name", suggestions = "arenas")
                          @Nullable String name) {
        Audience audience = source.source();
        Arena arena = resolve(audience, name, false);
        if (arena == null) {
            return;
        }
        if (plugin.arenas().busy()) {
            plugin.messages().send(audience, "busy");
            return;
        }
        plugin.messages().send(audience, "area.saving",
                Placeholder.unparsed("name", arena.name()),
                Placeholder.unparsed("blocks", String.valueOf(arena.region().volume())));

        plugin.arenas().capture(arena.name(), arena.region()).whenComplete((saved, error) ->
                plugin.scheduler().run(() -> {
                    if (error != null) {
                        fail(audience, "area.save-failed", error);
                        return;
                    }
                    plugin.messages().send(audience, "area.saved",
                            Placeholder.unparsed("name", saved.name()),
                            Placeholder.unparsed("blocks", String.valueOf(saved.region().volume())),
                            Placeholder.unparsed("size",
                                    kib(plugin.arenaStore().snapshotBytes(saved.name()))));
                }));
    }

    @Command("spleef area reset [name]")
    @CommandDescription("Restore an arena from its snapshot without running a game")
    @Permission("spleef.admin")
    public void reset(Source source, @Argument(value = "name", suggestions = "arenas")
                      @Nullable String name) {
        Audience audience = source.source();
        Arena arena = resolve(audience, name);
        if (arena == null) {
            return;
        }
        if (plugin.arenas().busy()) {
            plugin.messages().send(audience, "busy");
            return;
        }
        plugin.arenaStore().refreshWorldId(arena);

        long started = System.nanoTime();
        plugin.messages().send(audience, "area.resetting",
                Placeholder.unparsed("name", arena.name()));

        plugin.arenas().loadSnapshot(arena.name())
                .thenCompose(snapshot -> plugin.arenas().restore(snapshot))
                .whenComplete((changed, error) -> plugin.scheduler().run(() -> {
                    if (error != null) {
                        fail(audience, "area.reset-failed", error);
                        return;
                    }
                    plugin.messages().send(audience, "area.reset",
                            Placeholder.unparsed("name", arena.name()),
                            Placeholder.unparsed("blocks", String.valueOf(changed)),
                            Placeholder.unparsed("ms",
                                    String.valueOf((System.nanoTime() - started) / 1_000_000L)));
                }));
    }

    @Command("spleef area scan [name]")
    @CommandDescription("Dry-run the spawn scanner and report what it found")
    @Permission("spleef.admin")
    public void scan(Source source, @Argument(value = "name", suggestions = "arenas")
                     @Nullable String name) {
        Audience audience = source.source();
        Arena arena = resolve(audience, name, false);
        if (arena == null) {
            return;
        }
        World world = arena.region().world();
        if (world == null) {
            plugin.messages().send(audience, "game.world-missing",
                    Placeholder.unparsed("world", arena.region().worldName()));
            return;
        }
        if (plugin.arenas().busy()) {
            plugin.messages().send(audience, "busy");
            return;
        }

        Scatter.scan(plugin.scheduler(), world, arena.region(), plugin.config())
                .result()
                .whenComplete((report, error) -> plugin.scheduler().run(() -> {
                    if (error != null) {
                        fail(audience, "area.scan-failed", error);
                        return;
                    }
                    plugin.messages().send(audience, "area.scan",
                            Placeholder.unparsed("name", arena.name()),
                            Placeholder.unparsed("candidates",
                                    String.valueOf(report.candidates().size())),
                            Placeholder.unparsed("y", String.valueOf(report.platformY())),
                            Placeholder.unparsed("columns",
                                    String.valueOf(report.scannedColumns())),
                            Placeholder.unparsed("nofloor", String.valueOf(report.noFloor())),
                            Placeholder.unparsed("noheadroom",
                                    String.valueOf(report.noHeadroom())),
                            Placeholder.unparsed("lower", String.valueOf(report.belowPlatform())));
                    plugin.getSLF4JLogger().info(
                            "Scan of '{}': {} spawn candidates on deck y={} "
                                    + "({} columns, {} without floor, {} without headroom, "
                                    + "{} on a lower deck)",
                            arena.name(), report.candidates().size(), report.platformY(),
                            report.scannedColumns(), report.noFloor(), report.noHeadroom(),
                            report.belowPlatform());
                }));
    }

    @Command("spleef area use <name>")
    @CommandDescription("Set the arena that /spleef prepare uses by default")
    @Permission("spleef.admin")
    public void use(Source source, @Argument(value = "name", suggestions = "arenas") String name) {
        Audience audience = source.source();
        Arena arena = plugin.arenaStore().get(name).orElse(null);
        if (arena == null) {
            plugin.messages().send(audience, "area.unknown", Placeholder.unparsed("name", name));
            return;
        }
        plugin.activeArena(arena.name());
        plugin.messages().send(audience, "area.now-active",
                Placeholder.unparsed("name", arena.name()));
    }

    @Command("spleef area list")
    @CommandDescription("List saved arenas")
    @Permission("spleef.admin")
    public void list(Source source) {
        Audience audience = source.source();
        List<String> names = plugin.arenaStore().names();
        if (names.isEmpty()) {
            plugin.messages().send(audience, "area.none");
            return;
        }
        plugin.messages().send(audience, "area.list",
                Placeholder.unparsed("count", String.valueOf(names.size())),
                Placeholder.unparsed("names", String.join(", ", names)),
                Placeholder.unparsed("active", activeName()));
    }

    @Command("spleef area info [name]")
    @CommandDescription("Show an arena's bounds and snapshot state")
    @Permission("spleef.admin")
    public void info(Source source, @Argument(value = "name", suggestions = "arenas")
                     @Nullable String name) {
        Audience audience = source.source();
        Arena arena = resolve(audience, name, false);
        if (arena == null) {
            return;
        }
        plugin.messages().send(audience, "area.info",
                Placeholder.unparsed("name", arena.name()),
                Placeholder.unparsed("bounds", arena.region().describe()),
                Placeholder.unparsed("saved", arena.savedAt().toString()),
                Placeholder.unparsed("size", kib(plugin.arenaStore().snapshotBytes(arena.name()))),
                Placeholder.unparsed("entities", String.valueOf(arena.blockEntities())),
                Placeholder.unparsed("loaded", arena.region().world() == null ? "no" : "yes"));
    }

    @Command("spleef area delete <name> confirm")
    @CommandDescription("Delete an arena and its snapshot")
    @Permission("spleef.admin")
    public void delete(Source source, @Argument(value = "name", suggestions = "arenas") String name) {
        Audience audience = source.source();
        if (!plugin.arenaStore().delete(name)) {
            plugin.messages().send(audience, "area.unknown", Placeholder.unparsed("name", name));
            return;
        }
        if (plugin.config().activeArena.equalsIgnoreCase(name)) {
            plugin.activeArena("");
        }
        plugin.messages().send(audience, "area.deleted", Placeholder.unparsed("name", name));
    }

    @Suggestions("arenas")
    public List<String> arenaNames(CommandContext<Source> context, CommandInput input) {
        return plugin.arenaStore().names();
    }

    // --- helpers ---

    private @Nullable Arena resolve(Audience audience, @Nullable String name) {
        return resolve(audience, name, true);
    }

    /**
     * Resolves an explicit name, else the active arena, messaging on failure.
     *
     * @param requireSnapshot false for commands that are meant to work on an
     *                        arena that has no snapshot yet — you must be able
     *                        to take the first one
     */
    private @Nullable Arena resolve(Audience audience, @Nullable String name,
                                    boolean requireSnapshot) {
        String target = name != null ? name : plugin.config().activeArena;
        if (target == null || target.isBlank()) {
            plugin.messages().send(audience, "area.no-active");
            return null;
        }
        Arena arena = plugin.arenaStore().get(target).orElse(null);
        if (arena == null) {
            plugin.messages().send(audience, "area.unknown", Placeholder.unparsed("name", target));
            return null;
        }
        if (requireSnapshot && !plugin.arenaStore().hasSnapshot(arena.name())) {
            plugin.messages().send(audience, "area.no-snapshot",
                    Placeholder.unparsed("name", arena.name()));
            return null;
        }
        return arena;
    }

    private void fail(Audience audience, String key, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        plugin.getSLF4JLogger().error("Arena operation failed", cause);
        plugin.messages().send(audience, key,
                Placeholder.unparsed("reason", String.valueOf(cause.getMessage())));
    }

    private String activeName() {
        String active = plugin.config().activeArena;
        return active == null || active.isBlank() ? "none" : active;
    }

    private static String kib(long bytes) {
        return bytes < 0 ? "?" : String.format("%.1f KiB", bytes / 1024.0);
    }
}
