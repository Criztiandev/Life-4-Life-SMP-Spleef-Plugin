package dev.criztian.spleef.arena;

import org.bukkit.Location;
import org.bukkit.World;

/** A chunk coordinate pair, used to fan region work out to the owning region thread. */
public record ChunkPos(int x, int z) {

    public static ChunkPos ofBlock(int blockX, int blockZ) {
        return new ChunkPos(blockX >> 4, blockZ >> 4);
    }

    public int minBlockX() {
        return x << 4;
    }

    public int minBlockZ() {
        return z << 4;
    }

    public int maxBlockX() {
        return (x << 4) + 15;
    }

    public int maxBlockZ() {
        return (z << 4) + 15;
    }

    /**
     * A location inside this chunk, used only to pick the owning region thread
     * for {@code SchedulerService.runAt}. Y is irrelevant to region ownership.
     */
    public Location center(World world) {
        return new Location(world, minBlockX() + 8.0, 64.0, minBlockZ() + 8.0);
    }
}
