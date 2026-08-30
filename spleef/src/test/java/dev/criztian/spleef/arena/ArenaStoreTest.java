package dev.criztian.spleef.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class ArenaStoreTest {

    private static final UUID WORLD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static ArenaStore store(Path dataFolder) {
        return new ArenaStore(dataFolder, NOPLogger.NOP_LOGGER);
    }

    private static Arena arena(String name) {
        return new Arena(name,
                new Cuboid(WORLD_ID, "world", -10, 99, -10, 9, 103, 9),
                Instant.parse("2026-08-30T04:22:09.816Z"), 3);
    }

    private static void writeArenaFile(Path dataFolder, String fileName, String body)
            throws IOException {
        Path dir = dataFolder.resolve("arenas");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), body);
    }

    // --- name validation ---

    @Test
    void rejectsNamesThatWouldEscapeTheArenasDirectory() {
        assertFalse(ArenaStore.validName("../../etc/passwd"));
        assertFalse(ArenaStore.validName("a/b"));
        assertFalse(ArenaStore.validName("a\\b"));
        assertFalse(ArenaStore.validName(".."));
        assertFalse(ArenaStore.validName("main.yml"));
        assertFalse(ArenaStore.validName(""));
        assertFalse(ArenaStore.validName("   "));
        assertFalse(ArenaStore.validName("a".repeat(49)));

        assertTrue(ArenaStore.validName("main"));
        assertTrue(ArenaStore.validName("Arena_2-final"));
        assertTrue(ArenaStore.validName("a".repeat(48)));
    }

    @Test
    void lookupIsCaseInsensitiveButKeepsTheNameAsTyped(@TempDir Path dir) throws Exception {
        ArenaStore store = store(dir);
        store.save(arena("MainArena"));

        assertTrue(store.get("mainarena").isPresent());
        assertTrue(store.get("MAINARENA").isPresent());
        assertEquals("MainArena", store.get("mainarena").orElseThrow().name(),
                "display name must survive as the operator typed it");
    }

    // --- persistence round-trip ---

    @Test
    void savesAndReloadsAnArenaExactly(@TempDir Path dir) throws Exception {
        ArenaStore store = store(dir);
        Arena original = arena("main");
        store.save(original);

        ArenaStore reopened = store(dir);
        reopened.loadAll();
        Arena back = reopened.get("main").orElseThrow();

        assertEquals(original.name(), back.name());
        assertEquals(original.region(), back.region());
        assertEquals(original.blockEntities(), back.blockEntities());
        assertEquals(original.savedAt().toEpochMilli(), back.savedAt().toEpochMilli(),
                "timestamp must survive a save/load cycle");
    }

    /**
     * Regression: YAML resolves a bare ISO-8601 timestamp to a {@code Date}, so
     * reading it back as a string and calling {@code Instant.parse} threw and
     * took the whole arena down over a cosmetic field.
     */
    @Test
    void loadsAnArenaWhoseTimestampYamlParsedAsADate(@TempDir Path dir) throws Exception {
        writeArenaFile(dir, "legacy.yml", """
                name: legacy
                world-id: 11111111-2222-3333-4444-555555555555
                world-name: world
                min: {x: -10, y: 99, z: -10}
                max: {x: 9, y: 103, z: 9}
                saved-at: 2026-08-30T00:00:00Z
                block-entities: 0
                """);

        ArenaStore store = store(dir);
        store.loadAll();

        assertTrue(store.get("legacy").isPresent(),
                "an unparseable timestamp must not discard the arena");
        assertEquals(-10, store.get("legacy").orElseThrow().region().minX());
    }

    @Test
    void loadsAnArenaWithNoTimestampAtAll(@TempDir Path dir) throws Exception {
        writeArenaFile(dir, "bare.yml", """
                name: bare
                world-id: 11111111-2222-3333-4444-555555555555
                world-name: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                """);

        ArenaStore store = store(dir);
        store.loadAll();

        assertTrue(store.get("bare").isPresent());
        assertEquals(Instant.EPOCH, store.get("bare").orElseThrow().savedAt());
    }

    /**
     * Arena files get hand-written and copied between servers, where the world
     * UUID differs or was never filled in. The name is enough to find the world,
     * so a missing id must not throw the arena away.
     */
    @Test
    void loadsAnArenaWithNoWorldId(@TempDir Path dir) throws Exception {
        writeArenaFile(dir, "handwritten.yml", """
                name: handwritten
                world-name: world
                min: {x: -5, y: 60, z: -5}
                max: {x: 5, y: 70, z: 5}
                """);

        ArenaStore store = store(dir);
        store.loadAll();

        Arena loaded = store.get("handwritten").orElseThrow(
                () -> new AssertionError("a hand-written arena without a world id was discarded"));
        assertEquals("world", loaded.region().worldName());
        assertEquals(-5, loaded.region().minX());
    }

    @Test
    void loadsAnArenaWithAMalformedWorldId(@TempDir Path dir) throws Exception {
        writeArenaFile(dir, "broken.yml", """
                name: broken
                world-id: not-a-uuid
                world-name: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                """);

        ArenaStore store = store(dir);
        store.loadAll();

        assertTrue(store.get("broken").isPresent(),
                "a malformed world id should fall back to the world name, not discard the arena");
    }

    @Test
    void fallsBackToTheFileNameWhenTheNameFieldIsMissing(@TempDir Path dir) throws Exception {
        writeArenaFile(dir, "unnamed.yml", """
                world-id: 11111111-2222-3333-4444-555555555555
                world-name: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                """);

        ArenaStore store = store(dir);
        store.loadAll();

        assertTrue(store.get("unnamed").isPresent());
        assertEquals("unnamed", store.get("unnamed").orElseThrow().name());
    }

    @Test
    void oneCorruptFileDoesNotStopTheOthersLoading(@TempDir Path dir) throws Exception {
        writeArenaFile(dir, "good.yml", """
                name: good
                world-id: 11111111-2222-3333-4444-555555555555
                world-name: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                """);
        writeArenaFile(dir, "corrupt.yml", "this: is: not: valid: yaml: [[[");

        ArenaStore store = store(dir);
        store.loadAll();

        assertTrue(store.get("good").isPresent(), "a broken neighbour must not take out a good arena");
    }

    @Test
    void ignoresNonYamlFilesIncludingSnapshotBackups(@TempDir Path dir) throws Exception {
        ArenaStore store = store(dir);
        store.save(arena("main"));
        Path arenas = dir.resolve("arenas");
        Files.writeString(arenas.resolve("main.snapshot"), "binary-ish");
        Files.writeString(arenas.resolve("main.snapshot.bak"), "binary-ish");
        Files.writeString(arenas.resolve("notes.txt"), "hello");

        ArenaStore reopened = store(dir);
        reopened.loadAll();

        assertEquals(1, reopened.names().size());
        assertEquals("main", reopened.names().get(0));
    }

    // --- deletion ---

    @Test
    void deleteRemovesMetadataSnapshotAndBackup(@TempDir Path dir) throws Exception {
        ArenaStore store = store(dir);
        store.save(arena("main"));
        Path arenas = dir.resolve("arenas");
        Files.writeString(arenas.resolve("main.snapshot"), "x");
        Files.writeString(arenas.resolve("main.snapshot.bak"), "x");

        assertTrue(store.delete("main"));

        assertFalse(Files.exists(arenas.resolve("main.yml")));
        assertFalse(Files.exists(arenas.resolve("main.snapshot")));
        assertFalse(Files.exists(arenas.resolve("main.snapshot.bak")),
                "a stale .bak would resurrect deleted arena data");
        assertTrue(store.get("main").isEmpty());
    }

    @Test
    void deleteReportsWhenThereWasNothingToDelete(@TempDir Path dir) {
        assertFalse(store(dir).delete("never-existed"));
    }

    @Test
    void hasSnapshotReflectsTheFileOnDisk(@TempDir Path dir) throws Exception {
        ArenaStore store = store(dir);
        store.save(arena("main"));

        assertFalse(store.hasSnapshot("main"));
        Files.writeString(dir.resolve("arenas").resolve("main.snapshot"), "x");
        assertTrue(store.hasSnapshot("main"));
        assertTrue(store.hasSnapshot("MAIN"), "snapshot lookup must be case-insensitive too");
    }

    @Test
    void savingTwiceOverwritesRatherThanDuplicating(@TempDir Path dir) throws Exception {
        ArenaStore store = store(dir);
        store.save(arena("main"));
        store.save(new Arena("main",
                new Cuboid(WORLD_ID, "world", 0, 0, 0, 5, 5, 5), Instant.now(), 0));

        ArenaStore reopened = store(dir);
        reopened.loadAll();

        assertEquals(1, reopened.names().size());
        assertEquals(5, reopened.get("main").orElseThrow().region().maxX());
        assertNotEquals(103, reopened.get("main").orElseThrow().region().maxY());
    }
}
