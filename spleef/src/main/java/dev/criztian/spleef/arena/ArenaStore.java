package dev.criztian.spleef.arena;

import dev.criztian.spleef.arena.snapshot.BlockSnapshot;
import dev.criztian.spleef.arena.snapshot.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Arenas on disk: {@code arenas/<name>.yml} for the metadata, and
 * {@code arenas/<name>.snapshot} for the gzipped block data.
 *
 * <p>Files rather than the SQL store on purpose — the snapshot blob is large and
 * opaque, a plain file is trivially backed up and inspected, and it needs no
 * schema migration. The item vault, which needs keyed access and transactions,
 * uses SQL instead.</p>
 *
 * <p>Metadata is read and written node by node rather than through Configurate's
 * object mapper, so nothing depends on how the mapper happens to treat records.</p>
 */
public final class ArenaStore {

    /** Stands in for an absent world id; resolution then falls back to the name. */
    private static final UUID NIL_WORLD_ID = new UUID(0L, 0L);

    private final Path directory;
    private final Logger logger;
    private final Map<String, Arena> arenas = new ConcurrentHashMap<>();

    public ArenaStore(Path dataFolder, Logger logger) {
        this.directory = dataFolder.resolve("arenas");
        this.logger = logger;
    }

    public static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Rejects names that would escape the arenas directory or break on disk. */
    public static boolean validName(String name) {
        return !name.isBlank() && name.length() <= 48 && name.matches("[A-Za-z0-9_-]+");
    }

    public void loadAll() {
        arenas.clear();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            logger.error("Could not create {}", directory, e);
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".yml")).forEach(path -> {
                try {
                    Arena arena = read(path);
                    arenas.put(normalize(arena.name()), arena);
                } catch (Exception e) {
                    logger.error("Skipping unreadable arena file {}", path.getFileName(), e);
                }
            });
        } catch (IOException e) {
            logger.error("Could not list {}", directory, e);
        }
        logger.info("Loaded {} spleef arena(s)", arenas.size());
    }

    public Optional<Arena> get(String name) {
        return Optional.ofNullable(arenas.get(normalize(name)));
    }

    public List<String> names() {
        return arenas.values().stream().map(Arena::name).sorted().toList();
    }

    public boolean hasSnapshot(String name) {
        return Files.isRegularFile(snapshotPath(name));
    }

    public void save(Arena arena) throws ConfigurateException {
        Path path = metaPath(arena.name());
        YamlConfigurationLoader loader = loaderFor(path);
        CommentedConfigurationNode root = loader.createNode();
        Cuboid region = arena.region();
        root.node("name").set(arena.name());
        root.node("world-id").set(region.worldId().toString());
        root.node("world-name").set(region.worldName());
        root.node("min", "x").set(region.minX());
        root.node("min", "y").set(region.minY());
        root.node("min", "z").set(region.minZ());
        root.node("max", "x").set(region.maxX());
        root.node("max", "y").set(region.maxY());
        root.node("max", "z").set(region.maxZ());
        root.node("saved-at").set(arena.savedAt().toEpochMilli());
        root.node("block-entities").set(arena.blockEntities());
        loader.save(root);
        arenas.put(normalize(arena.name()), arena);
    }

    public boolean delete(String name) {
        Arena removed = arenas.remove(normalize(name));
        try {
            Files.deleteIfExists(metaPath(name));
            Files.deleteIfExists(snapshotPath(name));
            Files.deleteIfExists(directory.resolve(normalize(name) + ".snapshot.bak"));
        } catch (IOException e) {
            logger.error("Could not delete arena files for {}", name, e);
        }
        return removed != null;
    }

    /** Blocking file I/O — call from an async context. */
    public void writeSnapshot(String name, BlockSnapshot snapshot) throws IOException {
        SnapshotCodec.write(snapshotPath(name), snapshot);
    }

    /** Blocking file I/O — call from an async context. */
    public BlockSnapshot readSnapshot(String name) throws IOException {
        return SnapshotCodec.read(snapshotPath(name));
    }

    public long snapshotBytes(String name) {
        try {
            return Files.size(snapshotPath(name));
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * Re-points an arena at a world that was re-created with a new UUID
     * (Multiverse does exactly this), so a stale id cannot orphan the arena.
     */
    public void refreshWorldId(Arena arena) {
        Cuboid region = arena.region();
        if (!region.worldIdIsStale()) {
            return;
        }
        World world = region.world();
        if (world == null) {
            return;
        }
        logger.warn("Arena '{}' referenced a stale world id; re-pointing at '{}'",
                arena.name(), world.getName());
        try {
            save(new Arena(arena.name(), region.withWorld(world), arena.savedAt(),
                    arena.blockEntities()));
        } catch (ConfigurateException e) {
            logger.error("Could not rewrite world id for arena {}", arena.name(), e);
        }
    }

    private Arena read(Path path) throws ConfigurateException {
        CommentedConfigurationNode root = loaderFor(path).load();
        String fallbackName = path.getFileName().toString().replaceFirst("\\.yml$", "");
        String name = root.node("name").getString(fallbackName);
        Cuboid region = new Cuboid(
                readWorldId(root.node("world-id").getString()),
                root.node("world-name").getString(""),
                root.node("min", "x").getInt(), root.node("min", "y").getInt(),
                root.node("min", "z").getInt(),
                root.node("max", "x").getInt(), root.node("max", "y").getInt(),
                root.node("max", "z").getInt());
        return new Arena(name, region, readInstant(root.node("saved-at")),
                root.node("block-entities").getInt());
    }

    /**
     * Reads the save timestamp in whatever shape it is on disk.
     *
     * <p>YAML resolves a bare ISO-8601 timestamp to a {@link Date} on load, so a
     * plain {@code Instant.parse} on the string form throws. The timestamp is
     * cosmetic, so this never fails the arena — it degrades to the epoch.</p>
     */
    private static Instant readInstant(ConfigurationNode node) {
        Object raw = node.raw();
        if (raw instanceof Date date) {
            return date.toInstant();
        }
        if (raw instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        String text = node.getString();
        if (text != null) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        return Instant.EPOCH;
    }

    /**
     * Reads the stored world id, tolerating a missing or malformed one.
     *
     * <p>Arena files get hand-written and copied between servers, where the id
     * is wrong or was never filled in. The world name is enough to resolve the
     * world ({@link Cuboid#world()} falls back to it, and {@link #refreshWorldId}
     * rewrites the id on the next save), so a bad id must not throw the arena
     * away.</p>
     */
    private static UUID readWorldId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NIL_WORLD_ID;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return NIL_WORLD_ID;
        }
    }

    private Path metaPath(String name) {
        return directory.resolve(normalize(name) + ".yml");
    }

    private Path snapshotPath(String name) {
        return directory.resolve(normalize(name) + ".snapshot");
    }

    private static YamlConfigurationLoader loaderFor(Path path) {
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .build();
    }
}
