package dev.criztian.spleef.arena.wand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/** Per-player wand selections. In-memory only — a selection dies with the session. */
public final class SelectionService {

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public Selection of(UUID player) {
        return selections.computeIfAbsent(player, ignored -> new Selection());
    }

    public @Nullable Selection peek(UUID player) {
        return selections.get(player);
    }

    public void clear(UUID player) {
        selections.remove(player);
    }
}
