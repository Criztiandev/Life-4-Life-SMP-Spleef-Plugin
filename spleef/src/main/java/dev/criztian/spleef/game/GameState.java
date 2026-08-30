package dev.criztian.spleef.game;

/**
 * Session lifecycle.
 *
 * <pre>
 * IDLE --prepare--> PREPARING --> ARMED --start--> RUNNING
 *                                   ^                 |
 *                                   |     (win / draw / timeout)
 *                                   |                 v
 *                              RESETTING &lt;-round n-- ROUND_OVER
 * any non-IDLE --end--> ENDING --> IDLE
 * </pre>
 */
public enum GameState {
    IDLE,
    PREPARING,
    ARMED,
    RUNNING,
    ROUND_OVER,
    RESETTING,
    ENDING;

    /**
     * True while an async block job or teleport fan-out is in flight. Every
     * command except /spleef status is refused in these states, which closes the
     * whole race-window class between long operations and operator input.
     */
    public boolean busy() {
        return this == PREPARING || this == RESETTING || this == ENDING;
    }

    public boolean active() {
        return this != IDLE;
    }
}
