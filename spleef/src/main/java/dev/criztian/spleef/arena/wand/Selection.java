package dev.criztian.spleef.arena.wand;

import dev.criztian.spleef.arena.Cuboid;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/** One player's in-progress corner selection. Mutable, lives only in memory. */
public final class Selection {

    private @Nullable Location first;
    private @Nullable Location second;

    public void first(Location location) {
        this.first = location.clone();
    }

    public void second(Location location) {
        this.second = location.clone();
    }

    public @Nullable Location first() {
        return first;
    }

    public @Nullable Location second() {
        return second;
    }

    public boolean complete() {
        return first != null && second != null;
    }

    /**
     * @throws IllegalStateException if either corner is unset
     * @throws IllegalArgumentException if the corners are in different worlds
     */
    public Cuboid toCuboid() {
        if (first == null || second == null) {
            throw new IllegalStateException("Selection is incomplete");
        }
        return Cuboid.of(first, second);
    }
}
