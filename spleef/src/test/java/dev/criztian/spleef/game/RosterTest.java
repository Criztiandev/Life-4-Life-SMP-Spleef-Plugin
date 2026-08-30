package dev.criztian.spleef.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class RosterTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static Roster roster(Path dir) {
        return new Roster(dir, NOPLogger.NOP_LOGGER);
    }

    @Test
    void tracksMembership(@TempDir Path dir) {
        Roster roster = roster(dir);

        assertTrue(roster.isEmpty());
        assertTrue(roster.add(ALICE));
        assertFalse(roster.add(ALICE), "adding twice must report no change");
        assertTrue(roster.contains(ALICE));
        assertEquals(1, roster.size());

        assertTrue(roster.remove(ALICE));
        assertFalse(roster.remove(ALICE));
        assertTrue(roster.isEmpty());
    }

    @Test
    void survivesARestart(@TempDir Path dir) {
        Roster original = roster(dir);
        original.add(ALICE);
        original.add(BOB);
        original.signupsOpen(true);

        // The whole point of persisting: a restart in the hour before an event
        // must not lose the signups.
        Roster reloaded = roster(dir);
        reloaded.load();

        assertEquals(2, reloaded.size());
        assertTrue(reloaded.contains(ALICE));
        assertTrue(reloaded.contains(BOB));
        assertTrue(reloaded.signupsOpen());
    }

    @Test
    void persistsClosedSignups(@TempDir Path dir) {
        Roster original = roster(dir);
        original.signupsOpen(true);
        original.signupsOpen(false);
        original.add(ALICE);

        Roster reloaded = roster(dir);
        reloaded.load();

        assertFalse(reloaded.signupsOpen());
        assertEquals(1, reloaded.size());
    }

    @Test
    void clearPersistsToo(@TempDir Path dir) {
        Roster original = roster(dir);
        original.add(ALICE);
        original.add(BOB);
        original.clear();

        Roster reloaded = roster(dir);
        reloaded.load();

        assertTrue(reloaded.isEmpty(), "a cleared roster must not come back from disk");
    }

    @Test
    void loadingWithNoFileYieldsAnEmptyClosedRoster(@TempDir Path dir) {
        Roster roster = roster(dir);
        roster.load();

        assertTrue(roster.isEmpty());
        assertFalse(roster.signupsOpen());
    }

    @Test
    void loadResetsPreviousStateRatherThanMerging(@TempDir Path dir) {
        Roster roster = roster(dir);
        roster.add(ALICE);
        roster.add(BOB);

        // Someone replaces roster.yml out from under the server, then /spleef reload.
        writeRoster(dir, "signups-open: false", "- " + CAROL);
        roster.load();

        assertEquals(1, roster.size(), "reload must replace, not merge");
        assertTrue(roster.contains(CAROL));
        assertFalse(roster.contains(ALICE));
    }

    @Test
    void skipsJunkLinesWithoutLosingValidEntries(@TempDir Path dir) {
        writeRoster(dir,
                "# a comment",
                "",
                "   ",
                "- " + ALICE,
                "not-a-uuid",
                "- " + BOB,
                "signups-open: true");

        Roster roster = roster(dir);
        roster.load();

        assertEquals(2, roster.size(), "one bad line must not discard the roster");
        assertTrue(roster.contains(ALICE));
        assertTrue(roster.contains(BOB));
        assertTrue(roster.signupsOpen());
    }

    @Test
    void acceptsEntriesWithoutTheListDash(@TempDir Path dir) {
        // Hand-edited files are a fact of life.
        writeRoster(dir, ALICE.toString(), "- " + BOB);

        Roster roster = roster(dir);
        roster.load();

        assertEquals(2, roster.size());
    }

    @Test
    void keepsSignupOrderStable(@TempDir Path dir) {
        Roster roster = roster(dir);
        roster.add(CAROL);
        roster.add(ALICE);
        roster.add(BOB);

        // prepare() walks members() and the scatter assigns spawns by index, so
        // an unordered set here would make reproducible rounds a lie.
        assertEquals(List.of(CAROL, ALICE, BOB), List.copyOf(roster.members()));

        Roster reloaded = roster(dir);
        reloaded.load();
        assertEquals(List.of(CAROL, ALICE, BOB), List.copyOf(reloaded.members()),
                "signup order must survive a restart too");
    }

    private static void writeRoster(Path dir, String... lines) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("roster.yml"), String.join("\n", lines) + "\n");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
