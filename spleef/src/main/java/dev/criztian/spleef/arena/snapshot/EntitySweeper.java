package dev.criztian.spleef.arena.snapshot;

import dev.criztian.spleef.arena.ChunkPos;
import dev.criztian.spleef.arena.Cuboid;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;

/**
 * Clears the litter a spleef round leaves behind — dropped items, thrown
 * snowballs, XP orbs, falling blocks — so it does not survive an arena reset.
 *
 * <p>Matching is by interface rather than {@code EntityType} constant, because
 * those constants drift between versions (DROPPED_ITEM to ITEM, PRIMED_TNT to TNT).</p>
 */
public final class EntitySweeper {

    private EntitySweeper() {}

    /** Must run on the chunk's owning region thread. */
    public static int sweepChunk(World world, ChunkPos pos, Cuboid region) {
        int removed = 0;
        for (Entity entity : world.getChunkAt(pos.x(), pos.z()).getEntities()) {
            if (isLitter(entity) && region.contains(entity.getLocation())) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private static boolean isLitter(Entity entity) {
        return entity instanceof Item
                || entity instanceof Projectile
                || entity instanceof ExperienceOrb
                || entity instanceof FallingBlock
                || entity instanceof TNTPrimed
                || entity instanceof Firework;
    }
}
