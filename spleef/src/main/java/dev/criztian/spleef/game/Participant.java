package dev.criztian.spleef.game;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** One player's state within a session. Mutated only on the global region thread. */
public final class Participant {

    private final UUID uuid;
    private final String name;
    private ParticipantStatus status = ParticipantStatus.ALIVE;

    public Participant(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public ParticipantStatus status() {
        return status;
    }

    public void status(ParticipantStatus status) {
        this.status = status;
    }

    public boolean eliminated() {
        return status == ParticipantStatus.ELIMINATED;
    }

    public @Nullable Player online() {
        return Bukkit.getPlayer(uuid);
    }

    /**
     * Alive for win-condition purposes: still standing AND still connected.
     * Counting an offline player as alive would let a rage-quit stall the round
     * forever, so quitting eliminates you.
     */
    public boolean countsAsAlive() {
        return status == ParticipantStatus.ALIVE && online() != null;
    }
}
