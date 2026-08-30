package dev.criztian.spleef.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Who is playing. Both routes the operator asked for are supported: players can
 * sign themselves up with {@code /spleef join} while signups are open, and the
 * operator can enrol everyone at once with {@code /spleef roster all}.
 *
 * <p>Persisted to {@code roster.yml} on every change so a restart in the hour
 * before an event does not lose the signups.</p>
 */
public final class Roster {

    private final Path file;
    private final Logger logger;
    private final Set<UUID> members = new LinkedHashSet<>();
    private boolean signupsOpen;

    public Roster(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("roster.yml");
        this.logger = logger;
    }

    public boolean signupsOpen() {
        return signupsOpen;
    }

    public void signupsOpen(boolean open) {
        this.signupsOpen = open;
        save();
    }

    public boolean contains(UUID player) {
        return members.contains(player);
    }

    /**
     * Signups in the order they were made.
     *
     * <p>Order is load-bearing: {@code prepare} walks this to build the player
     * list, and the scatter assigns spawn points by index. {@code Set.copyOf}
     * here would drop the ordering and make "the same round always scatters the
     * same way" quietly untrue.</p>
     */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public boolean add(UUID player) {
        boolean added = members.add(player);
        if (added) {
            save();
        }
        return added;
    }

    public boolean remove(UUID player) {
        boolean removed = members.remove(player);
        if (removed) {
            save();
        }
        return removed;
    }

    public void clear() {
        members.clear();
        save();
    }

    /** Enrols every online player who is not explicitly excluded. */
    public int addAllOnline() {
        int added = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("spleef.bypass")) {
                continue;
            }
            if (members.add(player.getUniqueId())) {
                added++;
            }
        }
        save();
        return added;
    }

    /** Display names, resolving offline players by their last known name. */
    public List<String> names() {
        return members.stream()
                .map(id -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) {
                        return online.getName();
                    }
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                    String name = offline.getName();
                    return name != null ? name : id.toString().substring(0, 8);
                })
                .sorted()
                .toList();
    }

    // --- persistence (a plain UUID-per-line file; nothing here needs YAML structure) ---

    public void load() {
        members.clear();
        signupsOpen = false;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Stream<String> lines = Files.lines(file)) {
            lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> {
                        if (line.startsWith("signups-open:")) {
                            signupsOpen = Boolean.parseBoolean(
                                    line.substring("signups-open:".length()).trim());
                            return;
                        }
                        try {
                            members.add(UUID.fromString(line.replaceFirst("^-\\s*", "")));
                        } catch (IllegalArgumentException e) {
                            logger.warn("Ignoring malformed roster entry: {}", line);
                        }
                    });
        } catch (IOException e) {
            logger.error("Could not read {}", file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder out = new StringBuilder("# Spleef signups. Managed by /spleef roster.\n");
            out.append("signups-open: ").append(signupsOpen).append('\n');
            for (UUID id : members) {
                out.append("- ").append(id).append('\n');
            }
            Files.writeString(file, out.toString());
        } catch (IOException e) {
            logger.error("Could not write {}", file, e);
        }
    }
}
