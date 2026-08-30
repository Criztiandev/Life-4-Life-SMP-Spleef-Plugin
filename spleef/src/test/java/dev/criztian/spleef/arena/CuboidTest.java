package dev.criztian.spleef.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.criztian.spleef.TestWorlds;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class CuboidTest {

    private static final UUID WORLD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Cuboid region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new Cuboid(WORLD_ID, "world", minX, minY, minZ, maxX, maxY, maxZ);
    }

    // --- construction from wand corners ---

    @Test
    void sortsCornersRegardlessOfClickOrder() {
        World world = TestWorlds.overworld();
        Cuboid a = Cuboid.of(new Location(world, 9, 103, 9), new Location(world, -10, 99, -10));
        Cuboid b = Cuboid.of(new Location(world, -10, 99, -10), new Location(world, 9, 103, 9));

        assertEquals(a, b, "corner order must not matter");
        assertEquals(-10, a.minX());
        assertEquals(99, a.minY());
        assertEquals(-10, a.minZ());
        assertEquals(9, a.maxX());
        assertEquals(103, a.maxY());
        assertEquals(9, a.maxZ());
    }

    @Test
    void clampsYIntoTheWorldsBuildableRange() {
        World world = TestWorlds.overworld(); // -64 .. 320 exclusive
        Cuboid region = Cuboid.of(
                new Location(world, 0, -5000, 0),
                new Location(world, 10, 5000, 10));

        assertEquals(-64, region.minY(), "must clamp to world floor");
        // getMaxHeight() is EXCLUSIVE — the top buildable block is one below.
        assertEquals(319, region.maxY(), "must clamp to maxHeight - 1, not maxHeight");
    }

    @Test
    void rejectsCornersInDifferentWorlds() {
        World overworld = TestWorlds.world("world", -64, 320);
        World nether = TestWorlds.world("world_nether", 0, 128);

        assertThrows(IllegalArgumentException.class, () -> Cuboid.of(
                new Location(overworld, 0, 64, 0),
                new Location(nether, 10, 64, 10)));
    }

    // --- geometry ---

    @Test
    void volumeDoesNotOverflowIntArithmetic() {
        // 2000 x 320 x 2000 = 1.28e9, which fits in an int, but 4000 x 320 x 4000
        // = 5.12e9 does not. Without the (long) cast on the first operand this
        // silently wraps negative and every volume guard stops working.
        Cuboid huge = region(0, 0, 0, 3999, 319, 3999);
        assertEquals(4000L * 320L * 4000L, huge.volume());
        assertTrue(huge.volume() > Integer.MAX_VALUE);
    }

    @Test
    void indexIsABijectionOntoZeroUntilVolume() {
        Cuboid region = region(-3, 60, -2, 4, 63, 5);
        int volume = (int) region.volume();
        Set<Integer> seen = new HashSet<>();

        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    int index = region.index(x, y, z);
                    assertTrue(index >= 0 && index < volume,
                            "index " + index + " out of range for " + x + "," + y + "," + z);
                    assertTrue(seen.add(index), "duplicate index " + index);
                }
            }
        }
        assertEquals(volume, seen.size(), "every slot must be used exactly once");
    }

    @Test
    void containsIsInclusiveOnBothCorners() {
        Cuboid region = region(-10, 99, -10, 9, 103, 9);

        assertTrue(region.contains(-10, 99, -10));
        assertTrue(region.contains(9, 103, 9));
        assertTrue(region.contains(0, 101, 0));
        assertFalse(region.contains(-11, 99, -10));
        assertFalse(region.contains(9, 104, 9));
        assertFalse(region.contains(10, 103, 9));
    }

    @Test
    void sizesCountBothEndpoints() {
        Cuboid region = region(-10, 99, -10, 9, 103, 9);
        assertEquals(20, region.sizeX());
        assertEquals(5, region.sizeY());
        assertEquals(20, region.sizeZ());
        assertEquals(2000L, region.volume());
    }

    // --- chunk decomposition ---

    @Test
    void chunksCoverEveryBlockAcrossNegativeCoordinates() {
        // Straddles the origin, where a naive x/16 instead of x>>4 rounds toward
        // zero and silently drops the chunk column at -1.
        Cuboid region = region(-20, 60, -20, 19, 62, 19);
        List<ChunkPos> chunks = region.chunks();

        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                int cx = x >> 4;
                int cz = z >> 4;
                assertTrue(chunks.contains(new ChunkPos(cx, cz)),
                        "chunk " + cx + "," + cz + " missing for block " + x + "," + z);
            }
        }
        // -20..19 spans chunk columns -2,-1,0,1 on each axis.
        assertEquals(16, chunks.size());
    }

    @Test
    void chunkBoundsMatchTheirBlockRange() {
        ChunkPos pos = new ChunkPos(-2, 3);
        assertEquals(-32, pos.minBlockX());
        assertEquals(-17, pos.maxBlockX());
        assertEquals(48, pos.minBlockZ());
        assertEquals(63, pos.maxBlockZ());
        assertEquals(pos, ChunkPos.ofBlock(-20, 50));
    }

    @Test
    void chunksAreNotDuplicated() {
        Cuboid region = region(0, 60, 0, 47, 62, 47);
        List<ChunkPos> chunks = region.chunks();
        assertEquals(chunks.size(), Set.copyOf(chunks).size(), "chunk list must be distinct");
        assertEquals(9, chunks.size());
    }
}
