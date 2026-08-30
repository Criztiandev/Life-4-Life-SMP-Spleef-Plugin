package dev.criztian.spleef.arena.snapshot;

import dev.criztian.framework.scheduler.SchedulerService;
import dev.criztian.framework.scheduler.TaskHandle;
import dev.criztian.spleef.arena.ChunkPos;
import dev.criztian.spleef.arena.ProgressHandle;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;

/**
 * Runs a job over every chunk of a region, a few chunks per tick, each on the
 * chunk's owning region thread.
 *
 * <p>Two rules make this Folia-safe, and neither is optional:</p>
 * <ul>
 *   <li>A chunk is always loaded with {@code getChunkAtAsync} <em>before</em> any
 *       {@code runAt} is scheduled for it — a region task queued for a region that
 *       never loads may never run, which would hang the caller forever.</li>
 *   <li>The chunk future's completing thread is not contractual, so the job always
 *       hops through {@code runAt} rather than running in {@code whenComplete}.</li>
 * </ul>
 *
 * <p>A watchdog fails the operation if no chunk makes progress, so a stalled job
 * surfaces as an error instead of a permanently stuck game state.</p>
 */
public final class ChunkFanOut {

    @FunctionalInterface
    public interface ChunkJob {
        /** Runs on the chunk's owning region thread. */
        void accept(World world, ChunkPos chunk) throws Exception;
    }

    private ChunkFanOut() {}

    public static ProgressHandle run(SchedulerService scheduler, World world, List<ChunkPos> chunks,
                                     int chunksPerTick, Duration watchdog, ChunkJob job) {
        ProgressHandle handle = new ProgressHandle(chunks.size());
        if (chunks.isEmpty()) {
            handle.complete();
            return handle;
        }

        Deque<ChunkPos> queue = new ArrayDeque<>(chunks);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        TaskHandle[] tasks = new TaskHandle[2];
        int budget = Math.max(1, chunksPerTick);

        Runnable stop = () -> {
            for (TaskHandle task : tasks) {
                if (task != null) {
                    task.cancel();
                }
            }
        };

        // The pump is the only thing that touches `queue`, and it only ever runs
        // on the global region thread, so the deque needs no synchronisation.
        tasks[0] = scheduler.runTimer(() -> {
            if (handle.future().isDone()) {
                stop.run();
                return;
            }
            if (handle.cancelled()) {
                stop.run();
                handle.completeCancelled();
                return;
            }
            while (inFlight.get() < budget && !queue.isEmpty()) {
                ChunkPos pos = queue.poll();
                inFlight.incrementAndGet();
                world.getChunkAtAsync(pos.x(), pos.z(), true).whenComplete((chunk, error) -> {
                    lastProgress.set(System.nanoTime());
                    if (error != null) {
                        inFlight.decrementAndGet();
                        handle.fail(error);
                        return;
                    }
                    scheduler.runAt(pos.center(world), () -> {
                        try {
                            job.accept(world, pos);
                        } catch (Throwable t) {
                            handle.fail(t);
                        } finally {
                            inFlight.decrementAndGet();
                            handle.step();
                            lastProgress.set(System.nanoTime());
                        }
                    });
                });
            }
            if (queue.isEmpty() && inFlight.get() == 0) {
                stop.run();
                handle.complete();
            }
        }, 1, 1);

        tasks[1] = scheduler.asyncTimer(() -> {
            if (handle.future().isDone()) {
                stop.run();
                return;
            }
            if (System.nanoTime() - lastProgress.get() > watchdog.toNanos()) {
                stop.run();
                handle.fail(new TimeoutException(
                        "No chunk progressed for " + watchdog.toSeconds() + "s — aborting"));
            }
        }, watchdog, watchdog);

        return handle;
    }
}
