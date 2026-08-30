package dev.criztian.spleef.scatter;

import dev.criztian.framework.scheduler.SchedulerService;
import dev.criztian.spleef.SpleefConfig;
import dev.criztian.spleef.arena.ChunkPos;
import dev.criztian.spleef.arena.Cuboid;
import dev.criztian.spleef.arena.ProgressHandle;
import dev.criztian.spleef.arena.snapshot.ChunkFanOut;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Works out where players stand at the start of a round.
 *
 * <p>Placement is deterministic, not random: the shuffle is seeded from the
 * session and round number, so a given round always scatters the same way. That
 * matters at a live event — if someone disputes a spawn, it can be reproduced.</p>
 */
public final class Scatter {

    /** A valid standing position: feet at {@code y}, with headroom above. */
    public record SpawnPoint(int x, int y, int z) {}

    /**
     * @param platformY       the deck players will be placed on
     * @param noFloor         columns with no solid block at all
     * @param noHeadroom      columns whose surface was covered
     * @param belowPlatform   columns rejected for being on a lower deck
     */
    public record ScanReport(List<SpawnPoint> candidates, int platformY, int scannedColumns,
                             int noFloor, int noHeadroom, int belowPlatform) {

        public boolean usable() {
            return !candidates.isEmpty();
        }
    }

    public record Job(ProgressHandle progress, CompletableFuture<ScanReport> result) {}

    private Scatter() {}

    // --- scanning ---

    /**
     * Finds every valid standing position on the arena's top deck.
     *
     * <p>Runs through the chunk fan-out rather than looping on one thread,
     * because block reads must happen on the owning region thread.</p>
     */
    public static Job scan(SchedulerService scheduler, World world, Cuboid region,
                           SpleefConfig config) {
        long columns = (long) region.sizeX() * region.sizeZ();
        if (columns > config.limits.maxScanColumns) {
            ProgressHandle failed = new ProgressHandle(0);
            IllegalArgumentException error = new IllegalArgumentException(
                    "Arena footprint is " + columns + " columns, limit is "
                            + config.limits.maxScanColumns);
            failed.fail(error);
            return new Job(failed, CompletableFuture.failedFuture(error));
        }

        ConcurrentLinkedQueue<SpawnPoint> found = new ConcurrentLinkedQueue<>();
        AtomicInteger noFloor = new AtomicInteger();
        AtomicInteger noHeadroom = new AtomicInteger();

        ProgressHandle progress = ChunkFanOut.run(scheduler, world, region.chunks(),
                config.capture.chunksPerTick, Duration.ofSeconds(config.capture.watchdogSeconds),
                (w, pos) -> scanChunk(w, pos, region, found, noFloor, noHeadroom));

        CompletableFuture<ScanReport> result = progress.future().thenApply(ignored ->
                selectDeck(new ArrayList<>(found), config.scatter.platformTolerance,
                        (int) columns, noFloor.get(), noHeadroom.get(), region.minY()));

        return new Job(progress, result);
    }

    /**
     * Narrows every standing position found to the one deck players start on.
     *
     * <p>Package-private so the deck choice can be tested without a server.</p>
     */
    static ScanReport selectDeck(List<SpawnPoint> all, int platformTolerance, int columns,
                                 int noFloor, int noHeadroom, int fallbackY) {
        if (all.isEmpty()) {
            return new ScanReport(List.of(), fallbackY, columns, noFloor, noHeadroom, 0);
        }
        int deck = dominantY(all);
        int tolerance = Math.max(0, platformTolerance);
        List<SpawnPoint> onDeck = all.stream()
                .filter(point -> Math.abs(point.y() - deck) <= tolerance)
                .toList();
        return new ScanReport(onDeck, deck, columns, noFloor, noHeadroom, all.size() - onDeck.size());
    }

    /**
     * The height most of the surface sits at.
     *
     * <p>Deliberately the modal height, not the highest. A real arena has
     * decoration — a fence, a lamp post, a lip around the edge — and taking the
     * maximum would shift the deck up to whatever the tallest stray block is,
     * which with a tight tolerance can exclude the entire platform. The height
     * with the most standing room is the platform, whatever else is in the box.</p>
     */
    private static int dominantY(List<SpawnPoint> points) {
        Map<Integer, Integer> histogram = new HashMap<>();
        for (SpawnPoint point : points) {
            histogram.merge(point.y(), 1, Integer::sum);
        }
        int best = points.get(0).y();
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> entry : histogram.entrySet()) {
            // Ties go to the higher deck: on a symmetric two-tier arena the top
            // one is the one players expect to start on.
            if (entry.getValue() > bestCount
                    || (entry.getValue() == bestCount && entry.getKey() > best)) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static void scanChunk(World world, ChunkPos pos, Cuboid region,
                                  ConcurrentLinkedQueue<SpawnPoint> out,
                                  AtomicInteger noFloor, AtomicInteger noHeadroom) {
        int fromX = Math.max(region.minX(), pos.minBlockX());
        int toX = Math.min(region.maxX(), pos.maxBlockX());
        int fromZ = Math.max(region.minZ(), pos.minBlockZ());
        int toZ = Math.min(region.maxZ(), pos.maxBlockZ());
        int ceiling = world.getMaxHeight() - 1;

        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                boolean floorFound = false;
                boolean headroomFound = false;
                for (int y = region.maxY(); y >= region.minY(); y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.isSolid()) {
                        continue;
                    }
                    floorFound = true;
                    int feet = y + 1;
                    int head = y + 2;
                    if (head <= ceiling
                            && world.getBlockAt(x, feet, z).isPassable()
                            && world.getBlockAt(x, head, z).isPassable()) {
                        out.add(new SpawnPoint(x, feet, z));
                        headroomFound = true;
                    }
                    break; // only the topmost surface of this column matters
                }
                if (!floorFound) {
                    noFloor.incrementAndGet();
                } else if (!headroomFound) {
                    noHeadroom.incrementAndGet();
                }
            }
        }
    }

    // --- placement ---

    /**
     * Picks {@code count} well-spread points.
     *
     * <p>Greedy over a seeded shuffle of a stably sorted pool. If the platform
     * is too small to honour {@code minSpacing}, the spacing is relaxed rather
     * than the placement failing — a cramped arena should still start.</p>
     *
     * <p>The stable sort before the shuffle is what makes this reproducible.
     * Remove it and seeding silently stops meaning anything.</p>
     */
    public static List<Location> choose(World world, Cuboid region, ScanReport report,
                                        int count, double minSpacing, long seed) {
        List<SpawnPoint> pool = new ArrayList<>(report.candidates());
        if (pool.isEmpty() || count <= 0) {
            // Callers check usable() first; this keeps a bad scan from turning
            // into a divide-by-zero in the middle of a live event.
            return List.of();
        }
        pool.sort(Comparator.comparingInt(SpawnPoint::x).thenComparingInt(SpawnPoint::z));
        Collections.shuffle(pool, new Random(seed));

        List<SpawnPoint> accepted = List.of();
        for (double spacing = Math.max(0.0, minSpacing); ; spacing *= 0.8) {
            accepted = greedy(pool, count, spacing);
            if (accepted.size() >= count || spacing <= 1.0) {
                break;
            }
        }

        List<Location> out = new ArrayList<>(count);
        Location centre = region.center(world);
        for (int i = 0; i < count; i++) {
            // Last resort on a tiny platform: reuse points. Players overlap for a
            // moment and push apart, which beats refusing to start the event.
            SpawnPoint point = accepted.get(i % accepted.size());
            out.add(facing(world, point, centre));
        }
        return out;
    }

    private static List<SpawnPoint> greedy(List<SpawnPoint> pool, int count, double spacing) {
        List<SpawnPoint> accepted = new ArrayList<>(count);
        double squared = spacing * spacing;
        for (SpawnPoint candidate : pool) {
            if (accepted.size() == count) {
                break;
            }
            boolean clear = true;
            for (SpawnPoint placed : accepted) {
                double dx = candidate.x() - placed.x();
                double dz = candidate.z() - placed.z();
                if (dx * dx + dz * dz < squared) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    private static Location facing(World world, SpawnPoint point, Location centre) {
        Location location = new Location(world, point.x() + 0.5, point.y(), point.z() + 0.5);
        double dx = centre.getX() - location.getX();
        double dz = centre.getZ() - location.getZ();
        location.setYaw((float) (Math.toDegrees(Math.atan2(-dx, dz))));
        location.setPitch(0f);
        return location;
    }
}
