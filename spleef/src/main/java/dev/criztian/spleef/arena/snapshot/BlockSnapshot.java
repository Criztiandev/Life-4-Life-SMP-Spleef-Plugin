package dev.criztian.spleef.arena.snapshot;

import dev.criztian.spleef.arena.Cuboid;
import java.util.List;

/**
 * Every block state in a region, as a palette of {@code BlockData.getAsString()}
 * values plus one palette index per block.
 *
 * <p>A real spleef arena has a handful of distinct states, so the index array
 * compresses to roughly one byte per block before gzip, and gzip then crushes
 * the repetition further.</p>
 *
 * <p><b>Block entities are not captured.</b> {@code BlockData} carries block
 * states, not tile-entity NBT, and Paper exposes no public block-NBT accessor.
 * A sign, banner, chest or skull that is destroyed comes back blank; untouched
 * ones survive because restore skips blocks that already match.</p>
 */
public record BlockSnapshot(Cuboid region, List<String> palette, int[] indices) {

    public int paletteIndexAt(int x, int y, int z) {
        return indices[region.index(x, y, z)];
    }
}
