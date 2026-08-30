package dev.criztian.spleef.game;

import dev.criztian.framework.scheduler.TaskHandle;
import dev.criztian.spleef.arena.Arena;
import dev.criztian.spleef.arena.snapshot.BlockSnapshot;
import dev.criztian.spleef.scatter.Scatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** One live event. Exactly one exists at a time; all mutation is on the global thread. */
public final class SpleefSession {

    private final String id;
    private final Arena arena;
    private final BlockSnapshot snapshot;
    /** Scanned once at prepare and reused every round — the platform is restored identically. */
    private final Scatter.ScanReport scan;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();

    private GameState state = GameState.PREPARING;
    private int round = 1;
    private int totalSeconds;
    private long deadlineNanos;
    private boolean timerRunning;
    private boolean resolved;

    private @Nullable TaskHandle timerTask;
    private @Nullable TaskHandle frozenTitleTask;

    public SpleefSession(String id, Arena arena, BlockSnapshot snapshot, Scatter.ScanReport scan) {
        this.id = id;
        this.arena = arena;
        this.snapshot = snapshot;
        this.scan = scan;
    }

    public String id() {
        return id;
    }

    public Arena arena() {
        return arena;
    }

    public BlockSnapshot snapshot() {
        return snapshot;
    }

    public Scatter.ScanReport scan() {
        return scan;
    }

    public GameState state() {
        return state;
    }

    public void state(GameState state) {
        this.state = state;
    }

    public int round() {
        return round;
    }

    public void round(int round) {
        this.round = round;
    }

    // --- participants ---

    public void add(Participant participant) {
        participants.put(participant.uuid(), participant);
    }

    public @Nullable Participant participant(UUID uuid) {
        return participants.get(uuid);
    }

    public boolean isParticipant(UUID uuid) {
        return participants.containsKey(uuid);
    }

    public Collection<Participant> participants() {
        return participants.values();
    }

    public List<Participant> alive() {
        return participants.values().stream().filter(Participant::countsAsAlive).toList();
    }

    /** Online participants, for titles and broadcasts. */
    public List<Player> onlinePlayers() {
        List<Player> players = new ArrayList<>(participants.size());
        for (Participant participant : participants.values()) {
            Player player = participant.online();
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    public Audience audience() {
        return Audience.audience(onlinePlayers());
    }

    /** Puts everyone back in play for a new round. */
    public void reviveAll() {
        for (Participant participant : participants.values()) {
            participant.status(ParticipantStatus.ALIVE);
        }
        resolved = false;
    }

    // --- round timing ---

    public void startTimer(int seconds) {
        this.totalSeconds = seconds;
        this.timerRunning = true;
        // An absolute deadline, not a tick count: a lag spike must not silently
        // lengthen the round.
        this.deadlineNanos = System.nanoTime() + seconds * 1_000_000_000L;
    }

    public int totalSeconds() {
        return totalSeconds;
    }

    public void totalSeconds(int seconds) {
        this.totalSeconds = seconds;
    }

    public int remainingSeconds() {
        // While a round is armed the clock has not started, so the full round is
        // still to come. Subtracting from an unset deadline would report 0:00 and
        // make /spleef status look like the round had already expired.
        if (!timerRunning) {
            return totalSeconds;
        }
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? 0 : (int) Math.ceil(remaining / 1_000_000_000.0);
    }

    // --- one-shot resolution guard ---

    public boolean resolved() {
        return resolved;
    }

    /** @return true the first time only, so a round can resolve exactly once */
    public boolean markResolved() {
        if (resolved) {
            return false;
        }
        resolved = true;
        return true;
    }

    // --- tasks ---

    public void timerTask(@Nullable TaskHandle task) {
        this.timerTask = task;
    }

    public void frozenTitleTask(@Nullable TaskHandle task) {
        this.frozenTitleTask = task;
    }

    public void cancelTimer() {
        timerRunning = false;
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    public void cancelFrozenTitle() {
        if (frozenTitleTask != null) {
            frozenTitleTask.cancel();
            frozenTitleTask = null;
        }
    }

    public void cancelAllTasks() {
        cancelTimer();
        cancelFrozenTitle();
    }
}
