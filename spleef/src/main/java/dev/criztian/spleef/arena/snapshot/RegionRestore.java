package dev.criztian.spleef.arena.snapshot;

import dev.criztian.framework.scheduler.SchedulerService;
import dev.criztian.spleef.arena.ChunkPos;
import dev.criztian.spleef.arena.Cuboid;
import dev.criztian.spleef.arena.ProgressHandle;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Writes a snapshot back into the world, a bounded number of blocks per tick.
 *
 * <p>The whole performance story is that a spleef round only changes the blocks
 * players dug out. Every block is compared first — cheaply on {@code Material},
 * then fully on {@code BlockData} only when the materials already match — and
 * written only when it actually differs. A 115k-block arena with 5k dug blocks
 * costs a 115k-read scan and 5k writes, not 115k writes.</p>
 */
public final class RegionRestore {

    public record Job(ProgressHandle progress, CompletableFuture<Integer> blocksChanged) {}

    private RegionRestore() {}

    /**
     * @param blocksPerTick total block budget per tick; the chunk fan-out width is
     *                      derived from it so one knob controls the real cost
     */
    public static Job start(SchedulerService scheduler, World world, BlockSnapshot snapshot,
                            int blocksPerTick, Duration watchdog, boolean sweepEntities) {
        Cuboid region = snapshot.region();

        // Bukkit.createBlockData's thread-safety is not contractual, and the
        // palette is at most a few hundred entries — decode once, here, on the
        // caller's (global region) thread. Never move this to an async thread.
        List<String> palette = snapshot.palette();
        BlockData[] decoded = new BlockData[palette.size()];
        for (int i = 0; i < decoded.length; i++) {
            decoded[i] = Bukkit.createBlockData(palette.get(i));
        }

        int blocksPerChunkSlice = Math.max(1, 256 * region.sizeY());
        int chunksPerTick = Math.max(1, blocksPerTick / blocksPerChunkSlice);

        AtomicInteger changed = new AtomicInteger();
        int[] indices = snapshot.indices();

        ProgressHandle progress = ChunkFanOut.run(scheduler, world, region.chunks(), chunksPerTick,
                watchdog, (w, pos) -> {
                    if (sweepEntities) {
                        EntitySweeper.sweepChunk(w, pos, region);
                    }
                    restoreChunk(w, pos, region, indices, decoded, changed);
                });

        return new Job(progress, progress.future().thenApply(ignored -> changed.get()));
    }

    private static void restoreChunk(World world, ChunkPos pos, Cuboid region, int[] indices,
                                     BlockData[] palette, AtomicInteger changed) {
        int fromX = Math.max(region.minX(), pos.minBlockX());
        int toX = Math.min(region.maxX(), pos.maxBlockX());
        int fromZ = Math.max(region.minZ(), pos.minBlockZ());
        int toZ = Math.min(region.maxZ(), pos.maxBlockZ());

        int local = 0;
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    BlockData target = palette[indices[region.index(x, y, z)]];
                    Block block = world.getBlockAt(x, y, z);
                    // Cheap filter first: a dug spleef floor is snow -> air, so the
                    // material differs and we never allocate a BlockData for it.
                    if (block.getType() == target.getMaterial()
                            && block.getBlockData().equals(target)) {
                        continue;
                    }
                    // applyPhysics=false: nothing should cascade, and it keeps the
                    // write cost flat regardless of how much of the floor is missing.
                    block.setBlockData(target, false);
                    local++;
                }
            }
        }
        changed.addAndGet(local);
    }
}
