package dev.criztian.spleef.arena;

import java.time.Instant;

/**
 * A named, saved region. The block snapshot lives beside it in its own file —
 * see {@link ArenaStore}.
 *
 * @param blockEntities how many block entities the region held when it was
 *                      snapshotted; their NBT is not captured, so a destroyed
 *                      sign or chest comes back blank
 */
public record Arena(String name, Cuboid region, Instant savedAt, int blockEntities) {

    public boolean hasBlockEntities() {
        return blockEntities > 0;
    }
}
