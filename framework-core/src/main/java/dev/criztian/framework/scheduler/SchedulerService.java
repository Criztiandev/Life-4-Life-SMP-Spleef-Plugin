package dev.criztian.framework.scheduler;

import dev.criztian.framework.plugin.Service;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe scheduling facade. Routes exclusively through Paper's four
 * region-aware schedulers (global / region / async / entity), which behave
 * identically on plain Paper — {@code Bukkit.getScheduler()} must never be
 * used anywhere in framework or plugin code.
 *
 * <p>Rule of thumb: entity-bound work → {@code runOn*}, location-bound work →
 * {@code runAt*}, global state → {@code run*}, I/O → {@code async*}.</p>
 */
public final class SchedulerService implements Service {

    private final Plugin plugin;

    /**
     * Every task handed out, so {@link #cancelAll()} can cover region- and
     * entity-scheduler tasks too: RegionScheduler has no bulk cancel, and
     * entity tasks live on per-entity schedulers.
     */
    private final Set<ScheduledTask> tracked = ConcurrentHashMap.newKeySet();

    public SchedulerService(Plugin plugin) {
        this.plugin = plugin;
    }

    // --- global region (tick-based) ---

    public TaskHandle run(Runnable task) {
        return track(Bukkit.getGlobalRegionScheduler().run(plugin, oneShot(task)));
    }

    public TaskHandle runLater(Runnable task, long delayTicks) {
        return track(Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin, oneShot(task), atLeastOne(delayTicks)));
    }

    public TaskHandle runTimer(Runnable task, long delayTicks, long periodTicks) {
        return track(Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, repeating(task), atLeastOne(delayTicks), atLeastOne(periodTicks)));
    }

    // --- region (location-bound) ---

    public TaskHandle runAt(Location location, Runnable task) {
        return track(Bukkit.getRegionScheduler().run(plugin, location, oneShot(task)));
    }

    public TaskHandle runAtLater(Location location, Runnable task, long delayTicks) {
        return track(Bukkit.getRegionScheduler()
                .runDelayed(plugin, location, oneShot(task), atLeastOne(delayTicks)));
    }

    public TaskHandle runAtTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        return track(Bukkit.getRegionScheduler()
                .runAtFixedRate(plugin, location, repeating(task), atLeastOne(delayTicks), atLeastOne(periodTicks)));
    }

    // --- entity-bound (retired runs if the entity is removed before execution) ---

    public TaskHandle runOn(Entity entity, Runnable task, @Nullable Runnable retired) {
        return track(entity.getScheduler().run(plugin, oneShot(task), retired));
    }

    public TaskHandle runOnLater(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks) {
        return track(entity.getScheduler()
                .runDelayed(plugin, oneShot(task), retired, atLeastOne(delayTicks)));
    }

    public TaskHandle runOnTimer(Entity entity, Runnable task, @Nullable Runnable retired,
                                 long delayTicks, long periodTicks) {
        return track(entity.getScheduler()
                .runAtFixedRate(plugin, repeating(task), retired, atLeastOne(delayTicks), atLeastOne(periodTicks)));
    }

    // --- async (time-based, off the game threads) ---

    public TaskHandle async(Runnable task) {
        return track(Bukkit.getAsyncScheduler().runNow(plugin, oneShot(task)));
    }

    public TaskHandle asyncLater(Runnable task, Duration delay) {
        return track(Bukkit.getAsyncScheduler()
                .runDelayed(plugin, oneShot(task), delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    public TaskHandle asyncTimer(Runnable task, Duration initialDelay, Duration period) {
        return track(Bukkit.getAsyncScheduler()
                .runAtFixedRate(plugin, repeating(task), initialDelay.toMillis(), period.toMillis(),
                        TimeUnit.MILLISECONDS));
    }

    // --- CompletableFuture / Executor bridges (thread-hopping: async DB → entity thread) ---

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, asyncExecutor());
    }

    public Executor asyncExecutor() {
        return command -> track(Bukkit.getAsyncScheduler().runNow(plugin, oneShot(command)));
    }

    public Executor globalExecutor() {
        return command -> track(Bukkit.getGlobalRegionScheduler().run(plugin, oneShot(command)));
    }

    /**
     * Executor bound to an entity's region thread.
     *
     * @throws RejectedExecutionException if the entity is already removed —
     *         dependent {@link CompletableFuture} stages then complete
     *         exceptionally instead of hanging forever. An entity removed
     *         after acceptance but before execution still drops the task
     *         silently; use {@link #runOn} with a retired callback when that
     *         matters.
     */
    public Executor entityExecutor(Entity entity) {
        return command -> {
            ScheduledTask task = entity.getScheduler().run(plugin, oneShot(command), null);
            if (task == null) {
                throw new RejectedExecutionException(
                        "Entity " + entity.getUniqueId() + " is retired — task rejected");
            }
            tracked.add(task);
        };
    }

    /** Cancels every task scheduled through this service, on all four schedulers. */
    public void cancelAll() {
        for (ScheduledTask task : tracked) {
            task.cancel();
        }
        tracked.clear();
        // Backstop for anything the registry missed.
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    @Override
    public void shutdown() {
        cancelAll();
    }

    /** Wraps a one-shot body so its task deregisters itself once it has run. */
    private java.util.function.Consumer<ScheduledTask> oneShot(Runnable body) {
        return task -> {
            try {
                body.run();
            } finally {
                tracked.remove(task);
            }
        };
    }

    /** Repeating tasks stay registered until cancelled. */
    private static java.util.function.Consumer<ScheduledTask> repeating(Runnable body) {
        return task -> body.run();
    }

    private TaskHandle track(@Nullable ScheduledTask task) {
        if (task == null) {
            return TaskHandle.NOOP;
        }
        tracked.add(task);
        return new TaskHandle(task);
    }

    private static long atLeastOne(long ticks) {
        return Math.max(1L, ticks);
    }
}
