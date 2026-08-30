package dev.criztian.spleef.arena;

import dev.criztian.framework.scheduler.SchedulerService;
import dev.criztian.spleef.SpleefConfig;
import dev.criztian.spleef.arena.snapshot.BlockSnapshot;
import dev.criztian.spleef.arena.snapshot.RegionCapture;
import dev.criztian.spleef.arena.snapshot.RegionRestore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Capture, reset, and chunk residency for arenas.
 *
 * <p>Only one block job runs at a time, and it is always cancellable — an
 * operator running {@code /spleef end} during a long reset must be able to abort
 * it rather than wait it out.</p>
 */
public final class ArenaService {

    private final Plugin plugin;
    private final SchedulerService scheduler;
    private final ArenaStore store;
    private final Supplier<SpleefConfig> config;
    private final Logger logger;

    private volatile @Nullable ProgressHandle active;
    private volatile boolean pinned;

    public ArenaService(Plugin plugin, SchedulerService scheduler, ArenaStore store,
                        Supplier<SpleefConfig> config, Logger logger) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.store = store;
        this.config = config;
        this.logger = logger;
    }

    public ArenaStore store() {
        return store;
    }

    public boolean busy() {
        ProgressHandle handle = active;
        return handle != null && !handle.future().isDone();
    }

    public @Nullable ProgressHandle activeJob() {
        return active;
    }

    /** Aborts any in-flight capture or reset. Safe to call when nothing is running. */
    public void cancelActive() {
        ProgressHandle handle = active;
        if (handle != null && !handle.future().isDone()) {
            handle.cancel();
        }
    }

    // --- capture ---

    /**
     * Snapshots a region and saves it as a named arena. Block reads are batched
     * across ticks; the encode and file write happen off the game threads.
     */
    public CompletableFuture<Arena> capture(String name, Cuboid region) {
        World world = region.world();
        if (world == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World '" + region.worldName() + "' is not loaded"));
        }
        SpleefConfig cfg = config.get();
        long volume = region.volume();
        if (volume > cfg.limits.maxVolume) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Region is " + volume + " blocks, limit is " + cfg.limits.maxVolume));
        }

        RegionCapture.Job job = RegionCapture.start(scheduler, world, region,
                cfg.capture.chunksPerTick, Duration.ofSeconds(cfg.capture.watchdogSeconds));
        active = job.progress();

        return job.result().thenApplyAsync(captured -> {
            try {
                store.writeSnapshot(name, captured.snapshot());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Arena arena = new Arena(name, region, Instant.now(), captured.blockEntities());
            try {
                store.save(arena);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
            return arena;
        }, scheduler.asyncExecutor());
    }

    /** Reads a snapshot off the game threads. */
    public CompletableFuture<BlockSnapshot> loadSnapshot(String name) {
        return scheduler.supplyAsync(() -> {
            try {
                return store.readSnapshot(name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // --- reset ---

    /**
     * Restores a snapshot into the world.
     *
     * <p>Players standing inside the region are lifted clear <em>first</em>, and
     * the lift is awaited — a block restored into a player's head suffocates
     * them. For the same reason the caller must await this future before
     * scattering anyone back in.</p>
     *
     * @return the number of blocks that actually differed and were rewritten
     */
    public CompletableFuture<Integer> restore(BlockSnapshot snapshot) {
        World world = snapshot.region().world();
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "World '" + snapshot.region().worldName() + "' is not loaded"));
        }
        SpleefConfig cfg = config.get();

        return liftPlayersClear(world, snapshot.region()).thenComposeAsync(ignored -> {
            RegionRestore.Job job = RegionRestore.start(scheduler, world, snapshot,
                    cfg.restore.blocksPerTick, Duration.ofSeconds(cfg.capture.watchdogSeconds),
                    cfg.restore.sweepEntities);
            active = job.progress();
            return job.blocksChanged();
        }, scheduler.globalExecutor());
    }

    private CompletableFuture<Void> liftPlayersClear(World world, Cuboid region) {
        List<CompletableFuture<Boolean>> lifts = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Location at = player.getLocation();
            if (!region.contains(at)) {
                continue;
            }
            Location safe = new Location(world, at.getX(), region.maxY() + 2.0, at.getZ(),
                    at.getYaw(), at.getPitch());
            lifts.add(player.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN));
        }
        if (lifts.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        logger.info("Lifted {} player(s) clear of the arena before restoring", lifts.size());
        return CompletableFuture.allOf(lifts.toArray(CompletableFuture[]::new));
    }

    // --- chunk residency ---

    /**
     * Keeps the arena's chunks loaded for the session, so neither a reset nor a
     * teleport ever races a chunk unload.
     */
    public void pin(Cuboid region) {
        World world = region.world();
        if (world == null) {
            return;
        }
        pinned = true;
        for (ChunkPos pos : region.chunks()) {
            scheduler.runAt(pos.center(world),
                    () -> world.addPluginChunkTicket(pos.x(), pos.z(), plugin));
        }
    }

    /**
     * Releases every ticket this plugin holds. Bukkit does not document
     * automatic cleanup on disable, so this is called explicitly on end, on
     * abort, and on shutdown.
     */
    public void unpinAll() {
        if (!pinned) {
            return;
        }
        pinned = false;
        for (World world : plugin.getServer().getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
    }
}
