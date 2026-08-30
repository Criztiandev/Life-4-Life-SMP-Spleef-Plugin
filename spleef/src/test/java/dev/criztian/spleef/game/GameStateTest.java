package dev.criztian.spleef.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.criztian.spleef.player.Hud;
import org.junit.jupiter.api.Test;

class GameStateTest {

    @Test
    void busyStatesAreExactlyTheOnesWithWorkInFlight() {
        // Every command except /spleef status is refused while busy, so this set
        // is the guard that keeps an impatient operator from racing a block job.
        assertTrue(GameState.PREPARING.busy());
        assertTrue(GameState.RESETTING.busy());
        assertTrue(GameState.ENDING.busy());

        assertFalse(GameState.IDLE.busy());
        assertFalse(GameState.ARMED.busy());
        assertFalse(GameState.RUNNING.busy());
        assertFalse(GameState.ROUND_OVER.busy());
    }

    @Test
    void onlyIdleCountsAsNoSession() {
        assertFalse(GameState.IDLE.active());
        for (GameState state : GameState.values()) {
            if (state != GameState.IDLE) {
                assertTrue(state.active(), state + " should count as an active session");
            }
        }
    }

    @Test
    void clockFormatsAsMinutesAndPaddedSeconds() {
        assertEquals("30:00", Hud.format(1800));
        assertEquals("0:00", Hud.format(0));
        assertEquals("0:09", Hud.format(9));
        assertEquals("0:59", Hud.format(59));
        assertEquals("1:00", Hud.format(60));
        assertEquals("9:05", Hud.format(545));
    }

    @Test
    void clockNeverShowsNegativeTime() {
        // The timer tick can observe a deadline slightly in the past.
        assertEquals("0:00", Hud.format(-1));
        assertEquals("0:00", Hud.format(Integer.MIN_VALUE));
    }
}
