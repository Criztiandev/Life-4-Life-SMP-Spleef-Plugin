package dev.criztian.spleef.arena.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.criztian.spleef.arena.Cuboid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotCodecTest {

    private static final UUID WORLD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** A snapshot whose palette has {@code paletteSize} entries and uses every one. */
    private static BlockSnapshot withPalette(int paletteSize) {
        // Volume must equal indices.length — the reader allocates from the region.
        Cuboid region = new Cuboid(WORLD_ID, "world", 0, 0, 0, paletteSize - 1, 0, 0);
        List<String> palette = new ArrayList<>(paletteSize);
        int[] indices = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette.add("minecraft:test_" + i);
            indices[i] = i;
        }
        return new BlockSnapshot(region, List.copyOf(palette), indices);
    }

    private static void assertRoundTrips(Path file, BlockSnapshot original) throws IOException {
        SnapshotCodec.write(file, original);
        BlockSnapshot back = SnapshotCodec.read(file);

        assertEquals(original.region(), back.region(), "region must survive");
        assertEquals(original.palette(), back.palette(), "palette must survive");
        assertArrayEquals(original.indices(), back.indices(), "indices must survive");
    }

    @Test
    void roundTripsATypicalArena(@TempDir Path dir) throws IOException {
        Cuboid region = new Cuboid(WORLD_ID, "world", -10, 99, -10, 9, 103, 9);
        int[] indices = new int[(int) region.volume()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i % 3;
        }
        BlockSnapshot snapshot = new BlockSnapshot(region,
                List.of("minecraft:air", "minecraft:snow_block",
                        "minecraft:oak_sign[rotation=8,waterlogged=false]"),
                indices);

        assertRoundTrips(dir.resolve("arena.snapshot"), snapshot);
    }

    @Test
    void compressesARepetitiveArenaHard(@TempDir Path dir) throws IOException {
        // The whole storage argument rests on this: a real spleef arena is a
        // handful of block states repeated, so it must not cost 4 bytes a block.
        Cuboid region = new Cuboid(WORLD_ID, "world", -10, 99, -10, 9, 103, 9);
        int[] indices = new int[(int) region.volume()]; // all zero = all air
        BlockSnapshot snapshot = new BlockSnapshot(region,
                List.of("minecraft:air", "minecraft:snow_block"), indices);

        Path file = dir.resolve("arena.snapshot");
        SnapshotCodec.write(file, snapshot);

        assertTrue(Files.size(file) < 1024,
                "2000 uniform blocks should compress to well under 1 KiB, was "
                        + Files.size(file));
    }

    // --- palette width boundaries: one-off errors here corrupt every block ---

    @Test
    void roundTripsAtByteWidthUpperBound(@TempDir Path dir) throws IOException {
        // 256 entries still fits one byte per index (0..255). Reading these back
        // with a signed readByte would turn index 255 into -1.
        assertRoundTrips(dir.resolve("b256.snapshot"), withPalette(256));
    }

    @Test
    void roundTripsJustPastByteWidth(@TempDir Path dir) throws IOException {
        assertRoundTrips(dir.resolve("b257.snapshot"), withPalette(257));
    }

    @Test
    void roundTripsAtShortWidthUpperBound(@TempDir Path dir) throws IOException {
        // 65536 entries = max index 65535, the last value an unsigned short holds.
        assertRoundTrips(dir.resolve("s65536.snapshot"), withPalette(65536));
    }

    @Test
    void roundTripsJustPastShortWidth(@TempDir Path dir) throws IOException {
        assertRoundTrips(dir.resolve("i65537.snapshot"), withPalette(65537));
    }

    // --- file handling ---

    @Test
    void rotatesThePreviousSnapshotToBakAndLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("arena.snapshot");
        SnapshotCodec.write(file, withPalette(4));
        SnapshotCodec.write(file, withPalette(8));

        assertTrue(Files.exists(file));
        assertTrue(Files.exists(dir.resolve("arena.snapshot.bak")), "previous version kept");
        assertFalse(Files.exists(dir.resolve("arena.snapshot.tmp")), "temp file must be cleaned up");

        // The live file is the newer one; the .bak is the older one.
        assertEquals(8, SnapshotCodec.read(file).palette().size());
        assertEquals(4, SnapshotCodec.read(dir.resolve("arena.snapshot.bak")).palette().size());
    }

    @Test
    void rejectsAFileThatIsNotASnapshot(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bogus.snapshot");
        Files.write(file, "definitely not gzip".getBytes());

        assertThrows(IOException.class, () -> SnapshotCodec.read(file));
    }

    @Test
    void rejectsAnUnknownFormatVersionWithAnActionableMessage(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("future.snapshot");
        SnapshotCodec.write(file, withPalette(4));

        // Flip the version field inside the gzip stream by rewriting it wholesale.
        byte[] raw = Files.readAllBytes(file);
        byte[] plain = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(raw)).readAllBytes();
        plain[7] = 99; // version is the second int
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(plain);
        }

        IOException error = assertThrows(IOException.class, () -> SnapshotCodec.read(file));
        assertTrue(error.getMessage().contains("re-save"),
                "operator needs to be told what to do, got: " + error.getMessage());
    }

    @Test
    void preservesNegativeCoordinatesAndWorldIdentity(@TempDir Path dir) throws IOException {
        Cuboid region = new Cuboid(WORLD_ID, "spleef_arena",
                -1000, -64, -2000, -900, -60, -1900);
        int[] indices = new int[(int) region.volume()];
        BlockSnapshot snapshot = new BlockSnapshot(region, List.of("minecraft:air"), indices);

        SnapshotCodec.write(dir.resolve("neg.snapshot"), snapshot);
        BlockSnapshot back = SnapshotCodec.read(dir.resolve("neg.snapshot"));

        assertEquals(WORLD_ID, back.region().worldId());
        assertEquals("spleef_arena", back.region().worldName());
        assertEquals(region, back.region());
    }
}
