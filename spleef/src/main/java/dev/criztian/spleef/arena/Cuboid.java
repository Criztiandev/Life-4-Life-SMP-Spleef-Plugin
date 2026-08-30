package dev.criztian.spleef.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable axis-aligned block region, inclusive on both corners.
 *
 * <p>The world is stored as both a UUID and a name on purpose: Multiverse can
 * delete and re-create a world with a fresh UUID, which would orphan an
 * id-only reference. {@link #world()} falls back to the name, and
 * {@code ArenaStore} rewrites the stored id when that fallback fires.</p>
 */
public record Cuboid(UUID worldId, String worldName,
                     int minX, int minY, int minZ,
                     int maxX, int maxY, int maxZ) {

    /**
     * Builds a cuboid from two arbitrary corners, sorting them and clamping Y
     * into the world's buildable range.
     *
     * @throws IllegalArgumentException if the corners are in different worlds
     */
    public static Cuboid of(Location a, Location b) {
        World world = a.getWorld();
        if (world == null || b.getWorld() == null || !world.equals(b.getWorld())) {
            throw new IllegalArgumentException("Both corners must be in the same world");
        }
        // getMaxHeight() is EXCLUSIVE — the top buildable block is one below it.
        int floor = world.getMinHeight();
        int ceiling = world.getMaxHeight() - 1;
        return new Cuboid(world.getUID(), world.getName(),
                Math.min(a.getBlockX(), b.getBlockX()),
                Math.max(floor, Math.min(Math.min(a.getBlockY(), b.getBlockY()), ceiling)),
                Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()),
                Math.min(ceiling, Math.max(Math.max(a.getBlockY(), b.getBlockY()), floor)),
                Math.max(a.getBlockZ(), b.getBlockZ()));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** Total block count. The first cast is load-bearing — int math overflows here. */
    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Index of a block in a flat array laid out x-major, then y, then z. */
    public int index(int x, int y, int z) {
        return ((x - minX) * sizeY() + (y - minY)) * sizeZ() + (z - minZ);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location location) {
        World world = location.getWorld();
        return world != null
                && world.getUID().equals(worldId)
                && contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Resolves by UUID, falling back to the world name if the id went stale. */
    public @Nullable World world() {
        World byId = Bukkit.getWorld(worldId);
        return byId != null ? byId : Bukkit.getWorld(worldName);
    }

    /** True when {@link #world()} had to fall back to the name. */
    public boolean worldIdIsStale() {
        return Bukkit.getWorld(worldId) == null && Bukkit.getWorld(worldName) != null;
    }

    public Cuboid withWorld(World world) {
        return new Cuboid(world.getUID(), world.getName(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    public Location center(World world) {
        return new Location(world,
                (minX + maxX) / 2.0 + 0.5, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0 + 0.5);
    }

    /** Every chunk column this region touches, in a stable order. */
    public List<ChunkPos> chunks() {
        int fromX = minX >> 4;
        int toX = maxX >> 4;
        int fromZ = minZ >> 4;
        int toZ = maxZ >> 4;
        List<ChunkPos> out = new ArrayList<>((toX - fromX + 1) * (toZ - fromZ + 1));
        for (int cx = fromX; cx <= toX; cx++) {
            for (int cz = fromZ; cz <= toZ; cz++) {
                out.add(new ChunkPos(cx, cz));
            }
        }
        return out;
    }

    /** Human-readable bounds for status/info output. */
    public String describe() {
        return worldName + " (" + minX + "," + minY + "," + minZ + ")..("
                + maxX + "," + maxY + "," + maxZ + ") = "
                + sizeX() + "x" + sizeY() + "x" + sizeZ() + " = " + volume() + " blocks";
    }
}
