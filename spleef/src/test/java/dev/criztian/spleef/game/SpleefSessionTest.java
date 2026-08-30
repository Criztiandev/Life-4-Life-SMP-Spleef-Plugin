package dev.criztian.spleef.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.criztian.spleef.arena.Arena;
import dev.criztian.spleef.arena.Cuboid;
import dev.criztian.spleef.arena.snapshot.BlockSnapshot;
import dev.criztian.spleef.player.Hud;
import dev.criztian.spleef.scatter.Scatter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpleefSessionTest {

    private static SpleefSession session() {
        Cuboid region = new Cuboid(UUID.randomUUID(), "world", 0, 0, 0, 1, 1, 1);
        Arena arena = new Arena("main", region, Instant.EPOCH, 0);
        BlockSnapshot snapshot = new BlockSnapshot(region, List.of("minecraft:air"),
                new int[(int) region.volume()]);
        Scatter.ScanReport scan = new Scatter.ScanReport(
                List.of(new Scatter.SpawnPoint(0, 1, 0)), 1, 1, 0, 0, 0);
        return new SpleefSession("session-1", arena, snapshot, scan);
    }

    // --- the round clock ---

    @Test
    void anArmedRoundShowsTheFullDurationNotZero() {
        SpleefSession session = session();
        session.totalSeconds(1800);

        // Regression: subtracting from an unset deadline reported 0, so a frozen
        // lobby waiting on /spleef start displayed "0:00" as if time had expired.
        assertEquals(1800, session.remainingSeconds());
        assertEquals("30:00", Hud.format(session.remainingSeconds()));
    }

    @Test
    void aRunningRoundCountsDownFromTheFullDuration() {
        SpleefSession session = session();
        session.startTimer(1800);

        int remaining = session.remainingSeconds();
        assertTrue(remaining > 1790 && remaining <= 1800,
                "expected the clock to start near 1800, was " + remaining);
    }

    @Test
    void cancellingTheTimerParksTheClockAgain() {
        SpleefSession session = session();
        session.startTimer(600);
        session.cancelTimer();

        assertEquals(600, session.remainingSeconds(),
                "a reset round should show the full clock, not a stale countdown");
    }

    // --- one-shot resolution ---

    @Test
    void aRoundResolvesExactlyOnce() {
        SpleefSession session = session();

        // Two players dying in the same tick both reach evaluate(); only the
        // first may announce a result.
        assertTrue(session.markResolved());
        assertFalse(session.markResolved());
        assertFalse(session.markResolved());
        assertTrue(session.resolved());
    }

    @Test
    void aNewRoundCanResolveAgain() {
        SpleefSession session = session();
        session.markResolved();

        session.reviveAll();

        assertFalse(session.resolved(), "reviving must re-arm the win condition");
        assertTrue(session.markResolved());
    }

    // --- participants ---

    @Test
    void revivingPutsEliminatedPlayersBackInPlay() {
        SpleefSession session = session();
        UUID alice = UUID.randomUUID();
        Participant participant = new Participant(alice, "Alice");
        session.add(participant);
        participant.status(ParticipantStatus.ELIMINATED);

        session.reviveAll();

        assertEquals(ParticipantStatus.ALIVE, participant.status());
        assertFalse(participant.eliminated());
    }

    @Test
    void participantsKeepTheirJoinOrder() {
        SpleefSession session = session();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        session.add(new Participant(a, "A"));
        session.add(new Participant(b, "B"));
        session.add(new Participant(c, "C"));

        assertEquals(List.of("A", "B", "C"),
                session.participants().stream().map(Participant::name).toList());
    }

    @Test
    void addingTheSamePlayerTwiceDoesNotDuplicateThem() {
        SpleefSession session = session();
        UUID alice = UUID.randomUUID();
        session.add(new Participant(alice, "Alice"));
        session.add(new Participant(alice, "Alice"));

        assertEquals(1, session.participants().size());
        assertTrue(session.isParticipant(alice));
    }
}
