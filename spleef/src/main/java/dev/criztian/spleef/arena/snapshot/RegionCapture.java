package dev.criztian.spleef.arena.snapshot;

import dev.criztian.framework.scheduler.SchedulerService;
import dev.criztian.spleef.arena.ChunkPos;
import dev.criztian.spleef.arena.Cuboid;
import dev.criztian.spleef.arena.ProgressHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Reads every block state in a region into a {@link BlockSnapshot}, a few chunks per tick. */
public final class RegionCapture {

    /** @param blockEntities how many block entities were seen — their NBT is NOT captured */
    public record Captured(BlockSnapshot snapshot, int blockEntities) {}

    public record Job(ProgressHandle progress, CompletableFuture<Captured> result) {}

    private RegionCapture() {}

    public static Job start(SchedulerService scheduler, World world, Cuboid region,
                            int chunksPerTick, Duration watchdog) {
        long volume = region.volume();
        if (volume > Integer.MAX_VALUE) {
            ProgressHandle failed = new ProgressHandle(0);
            failed.fail(new IllegalArgumentException("Region too large: " + volume + " blocks"));
            return new Job(failed, CompletableFuture.failedFuture(
                    new IllegalArgumentException("Region too large: " + volume + " blocks")));
        }

        int[] indices = new int[(int) volume];
        // computeIfAbsent is atomic per key, so concurrent region threads cannot
        // hand out two different indices for the same block state.
        Map<String, Integer> paletteIndex = new ConcurrentHashMap<>();
        List<String> palette = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger blockEntities = new AtomicInteger();

        ProgressHandle progress = ChunkFanOut.run(scheduler, world, region.chunks(), chunksPerTick,
                watchdog, (w, pos) -> captureChunk(w, pos, region, indices, paletteIndex, palette,
                        blockEntities));

        CompletableFuture<Captured> result = progress.future().thenApply(ignored ->
                new Captured(new BlockSnapshot(region, List.copyOf(palette), indices),
                        blockEntities.get()));

        return new Job(progress, result);
    }

    private static void captureChunk(World world, ChunkPos pos, Cuboid region, int[] indices,
                                     Map<String, Integer> paletteIndex, List<String> palette,
                                     AtomicInteger blockEntities) {
        int fromX = Math.max(region.minX(), pos.minBlockX());
        int toX = Math.min(region.maxX(), pos.maxBlockX());
        int fromZ = Math.max(region.minZ(), pos.minBlockZ());
        int toZ = Math.min(region.maxZ(), pos.maxBlockZ());

        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    String state = block.getBlockData().getAsString();
                    int index = paletteIndex.computeIfAbsent(state, key -> {
                        synchronized (palette) {
                            palette.add(key);
                            return palette.size() - 1;
                        }
                    });
                    // Distinct chunks write disjoint slots, so the shared array is safe.
                    indices[region.index(x, y, z)] = index;
                }
            }
        }

        blockEntities.addAndGet(world.getChunkAt(pos.x(), pos.z())
                .getTileEntities(block -> region.contains(
                        block.getX(), block.getY(), block.getZ()), false)
                .size());
    }
}
