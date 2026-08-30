package dev.criztian.spleef.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.criztian.spleef.TestWorlds;
import dev.criztian.spleef.arena.Cuboid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class ScatterTest {

    private static final UUID WORLD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Cuboid arena() {
        return new Cuboid(WORLD_ID, "world", -10, 99, -10, 9, 103, 9);
    }

    /** A flat 20x20 deck at the given height, like the test platform. */
    private static List<Scatter.SpawnPoint> flatDeck(int y) {
        List<Scatter.SpawnPoint> points = new ArrayList<>();
        for (int x = -10; x <= 9; x++) {
            for (int z = -10; z <= 9; z++) {
                points.add(new Scatter.SpawnPoint(x, y, z));
            }
        }
        return points;
    }

    private static Scatter.ScanReport report(List<Scatter.SpawnPoint> points) {
        return Scatter.selectDeck(points, 2, points.size(), 0, 0, 99);
    }

    // --- deck selection ---

    @Test
    void picksTheDeckWithTheMostStandingRoomNotTheHighestBlock() {
        // One decorative pillar three blocks above an otherwise flat platform.
        // Taking the maximum height would call y=105 "the deck" and, with a
        // tolerance of 2, discard all 400 real spawns.
        List<Scatter.SpawnPoint> points = new ArrayList<>(flatDeck(101));
        points.add(new Scatter.SpawnPoint(0, 105, 0));

        Scatter.ScanReport result = Scatter.selectDeck(points, 2, 400, 0, 0, 99);

        assertEquals(101, result.platformY(), "deck must be the modal height");
        assertEquals(400, result.candidates().size(), "the whole platform must survive");
        assertEquals(1, result.belowPlatform(), "the pillar is the only thing dropped");
    }

    @Test
    void keepsMinorHeightVariationWithinTolerance() {
        List<Scatter.SpawnPoint> points = new ArrayList<>(flatDeck(101));
        points.add(new Scatter.SpawnPoint(0, 102, 0)); // a slab lip
        points.add(new Scatter.SpawnPoint(1, 100, 0)); // a dip

        Scatter.ScanReport result = Scatter.selectDeck(points, 2, 402, 0, 0, 99);

        assertEquals(101, result.platformY());
        assertEquals(402, result.candidates().size(), "±2 must all be kept");
        assertEquals(0, result.belowPlatform());
    }

    @Test
    void dropsALowerDeckOutsideTolerance() {
        List<Scatter.SpawnPoint> points = new ArrayList<>(flatDeck(101));
        points.addAll(flatDeck(90).subList(0, 50)); // a smaller second storey below

        Scatter.ScanReport result = Scatter.selectDeck(points, 2, 450, 0, 0, 99);

        assertEquals(101, result.platformY());
        assertEquals(400, result.candidates().size());
        assertEquals(50, result.belowPlatform());
    }

    @Test
    void reportsUnusableWhenNothingWasFound() {
        Scatter.ScanReport result = Scatter.selectDeck(List.of(), 2, 400, 400, 0, 99);

        assertFalse(result.usable());
        assertEquals(99, result.platformY(), "falls back to the region floor");
    }

    // --- placement ---

    @Test
    void placesExactlyAsManyPlayersAsAsked() {
        World world = TestWorlds.overworld();
        List<Location> spots = Scatter.choose(world, arena(), report(flatDeck(101)), 24, 4.0, 7L);

        assertEquals(24, spots.size());
    }

    @Test
    void isReproducibleForTheSameRoundAndDifferentAcrossRounds() {
        World world = TestWorlds.overworld();
        Scatter.ScanReport deck = report(flatDeck(101));

        List<Location> roundOneA = Scatter.choose(world, arena(), deck, 12, 4.0, 100L);
        List<Location> roundOneB = Scatter.choose(world, arena(), deck, 12, 4.0, 100L);
        List<Location> roundTwo = Scatter.choose(world, arena(), deck, 12, 4.0, 101L);

        assertEquals(roundOneA, roundOneB, "same seed must reproduce the same spawns");
        assertNotEquals(roundOneA, roundTwo, "a new round must scatter differently");
    }

    @Test
    void honoursMinimumSpacingWhenThePlatformIsRoomy() {
        World world = TestWorlds.overworld();
        List<Location> spots = Scatter.choose(world, arena(), report(flatDeck(101)), 8, 4.0, 42L);

        for (int i = 0; i < spots.size(); i++) {
            for (int j = i + 1; j < spots.size(); j++) {
                double dx = spots.get(i).getX() - spots.get(j).getX();
                double dz = spots.get(i).getZ() - spots.get(j).getZ();
                assertTrue(Math.sqrt(dx * dx + dz * dz) >= 4.0,
                        "spawns " + i + " and " + j + " are too close");
            }
        }
    }

    @Test
    void relaxesSpacingRatherThanFailingOnACrampedPlatform() {
        World world = TestWorlds.overworld();
        // 25 standing positions, 20 players, and an impossible 10-block spacing:
        // the event still has to start.
        List<Scatter.SpawnPoint> tiny = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                tiny.add(new Scatter.SpawnPoint(x, 101, z));
            }
        }
        List<Location> spots = Scatter.choose(world, arena(), report(tiny), 20, 10.0, 5L);

        assertEquals(20, spots.size(), "must still place everyone");
    }

    @Test
    void returnsNothingWhenTheScanFoundNoGround() {
        World world = TestWorlds.overworld();
        Scatter.ScanReport empty = Scatter.selectDeck(List.of(), 2, 0, 0, 0, 99);

        // The guard that stops an empty candidate pool becoming a divide-by-zero
        // in the middle of a live event.
        assertTrue(Scatter.choose(world, arena(), empty, 8, 4.0, 1L).isEmpty());
    }

    @Test
    void centresPlayersOnTheirBlock() {
        World world = TestWorlds.overworld();
        List<Location> spots = Scatter.choose(world, arena(), report(flatDeck(101)), 5, 4.0, 3L);

        for (Location spot : spots) {
            assertEquals(0.5, Math.abs(spot.getX() % 1), 1e-9, "x must sit on the block centre");
            assertEquals(0.5, Math.abs(spot.getZ() % 1), 1e-9, "z must sit on the block centre");
            assertEquals(101, spot.getY(), 1e-9, "feet go on the deck");
        }
    }

    @Test
    void everySpawnIsInsideTheArena() {
        World world = TestWorlds.overworld();
        Cuboid region = arena();
        List<Location> spots = Scatter.choose(world, region, report(flatDeck(101)), 30, 4.0, 9L);

        for (Location spot : spots) {
            assertTrue(region.contains(spot.getBlockX(), spot.getBlockY(), spot.getBlockZ()),
                    "spawn " + spot + " escaped the arena");
        }
    }
}
