package dev.criztian.spleef.player;

import dev.criztian.framework.storage.Migration;
import dev.criztian.framework.storage.StorageService;
import dev.criztian.spleef.SpleefConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Custody of players' real inventories for the duration of an event.
 *
 * <p>This is the highest-stakes code in the plugin, so it holds to four rules:</p>
 * <ol>
 *   <li><b>Never overwrite an existing row.</b> If a player already has a vault
 *       entry, whatever they are holding now is game gear — storing it over the
 *       original is the one mistake that destroys real items irrecoverably.</li>
 *   <li><b>Commit before clearing.</b> The row is written and committed first;
 *       only then is the inventory emptied. The reverse order has a window where
 *       a crash loses everything.</li>
 *   <li><b>Offline players are the join path's problem.</b> Rows simply stay
 *       pending, and the next login hands the items back — so a disconnect, a
 *       crash and a clean shutdown all share one recovery route.</li>
 *   <li><b>Delete only after a successful apply.</b></li>
 * </ol>
 */
public final class ItemVault {

    /** What {@link #vault} actually did. */
    public enum Outcome {
        /** Fresh row written and inventory cleared. */
        VAULTED,
        /** A row already existed; it was left untouched and only game gear was cleared. */
        ALREADY_VAULTED,
        /** Nothing was written — the inventory was left alone. */
        FAILED
    }

    public static final Migration MIGRATION = Migration.of(1, "spleef vault and session journal",
            "CREATE TABLE IF NOT EXISTS spleef_vault ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "player_name VARCHAR(32) NOT NULL, "
                    + "session_id VARCHAR(64) NOT NULL, "
                    + "contents BLOB NOT NULL, "
                    + "armor BLOB NOT NULL, "
                    + "extra BLOB NOT NULL, "
                    + "xp_level INT NOT NULL, "
                    + "xp_progress REAL NOT NULL, "
                    + "health REAL NOT NULL, "
                    + "food INT NOT NULL, "
                    + "saturation REAL NOT NULL, "
                    + "game_mode VARCHAR(16) NOT NULL, "
                    + "allow_flight INT NOT NULL, "
                    + "flying INT NOT NULL, "
                    + "world VARCHAR(64), "
                    + "x REAL, y REAL, z REAL, yaw REAL, pitch REAL, "
                    + "saved_at VARCHAR(32) NOT NULL, "
                    + "cleared_at VARCHAR(32))",
            // `open` is reserved in some dialects — is_open is not.
            "CREATE TABLE IF NOT EXISTS spleef_session ("
                    + "id VARCHAR(64) PRIMARY KEY, "
                    + "arena VARCHAR(64) NOT NULL, "
                    + "round INT NOT NULL, "
                    + "is_open INT NOT NULL, "
                    + "started_at VARCHAR(32) NOT NULL)");

    private final StorageService storage;
    private final Logger logger;

    public ItemVault(StorageService storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    // --- taking custody ---

    /**
     * Stores a player's whole state and empties their inventory.
     *
     * <p>Runs synchronously on purpose. For a hundred players this is tens of
     * milliseconds inside a one-shot admin command, and it removes the entire
     * class of race where the clear lands but the write does not.</p>
     */
    public Outcome vault(Player player, String sessionId) {
        UUID id = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();

        if (hasPending(id)) {
            logger.warn("{} already has a vaulted inventory — refusing to overwrite it. "
                    + "Clearing in-game gear only.", player.getName());
            clearInventory(player);
            return Outcome.ALREADY_VAULTED;
        }

        Location at = player.getLocation();
        try {
            storage.update("INSERT INTO spleef_vault (uuid, player_name, session_id, contents, "
                            + "armor, extra, xp_level, xp_progress, health, food, saturation, "
                            + "game_mode, allow_flight, flying, world, x, y, z, yaw, pitch, saved_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id.toString(),
                    player.getName(),
                    sessionId,
                    ItemStack.serializeItemsAsBytes(inventory.getContents()),
                    ItemStack.serializeItemsAsBytes(inventory.getArmorContents()),
                    ItemStack.serializeItemsAsBytes(inventory.getExtraContents()),
                    player.getLevel(),
                    player.getExp(),
                    player.getHealth(),
                    player.getFoodLevel(),
                    player.getSaturation(),
                    player.getGameMode().name(),
                    player.getAllowFlight() ? 1 : 0,
                    player.isFlying() ? 1 : 0,
                    at.getWorld() == null ? null : at.getWorld().getName(),
                    at.getX(), at.getY(), at.getZ(),
                    (double) at.getYaw(), (double) at.getPitch(),
                    Instant.now().toString());
        } catch (RuntimeException e) {
            // The inventory has NOT been touched — the player keeps everything.
            logger.error("Could not vault {}'s inventory; leaving it untouched", player.getName(), e);
            return Outcome.FAILED;
        }

        clearInventory(player);
        storage.update("UPDATE spleef_vault SET cleared_at = ? WHERE uuid = ?",
                Instant.now().toString(), id.toString());
        return Outcome.VAULTED;
    }

    // --- handing it back ---

    /**
     * Restores a player's state and drops their vault row.
     *
     * <p>Returns the record so the caller can still read the pre-game location
     * for the end-of-event teleport — the row is gone by then.</p>
     *
     * @return empty when the player had nothing vaulted
     */
    public Optional<VaultRecord> restore(Player player, SpleefConfig.RestoreMode mode) {
        VaultRecord record = load(player.getUniqueId()).orElse(null);
        if (record == null) {
            return Optional.empty();
        }
        apply(player, record, mode);
        storage.update("DELETE FROM spleef_vault WHERE uuid = ?", player.getUniqueId().toString());
        logger.info("Restored {}'s vaulted inventory", player.getName());
        return Optional.of(record);
    }

    private void apply(Player player, VaultRecord record, SpleefConfig.RestoreMode mode) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] leftovers = mode == SpleefConfig.RestoreMode.MERGE_DROP_OVERFLOW
                ? inventory.getContents().clone()
                : null;

        inventory.clear();
        inventory.setContents(record.contents());
        inventory.setArmorContents(record.armor());
        inventory.setExtraContents(record.extra());

        player.setLevel(record.xpLevel());
        player.setExp(record.xpProgress());
        player.setFoodLevel(record.food());
        player.setSaturation(record.saturation());
        player.setGameMode(record.gameMode());
        player.setAllowFlight(record.allowFlight());
        player.setFlying(record.allowFlight() && record.flying());

        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) != null
                ? player.getAttribute(Attribute.MAX_HEALTH).getValue()
                : 20.0;
        player.setHealth(Math.max(1.0, Math.min(record.health(), maxHealth)));

        if (leftovers != null) {
            for (ItemStack stack : leftovers) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                inventory.addItem(stack).values()
                        .forEach(overflow -> player.getWorld().dropItemNaturally(
                                player.getLocation(), overflow));
            }
        }
    }

    // --- queries ---

    public boolean hasPending(UUID player) {
        return storage.queryOne("SELECT 1 FROM spleef_vault WHERE uuid = ?",
                rs -> rs.getInt(1), player.toString()).isPresent();
    }

    public Optional<VaultRecord> load(UUID player) {
        return storage.queryOne(
                "SELECT uuid, player_name, session_id, contents, armor, extra, xp_level, "
                        + "xp_progress, health, food, saturation, game_mode, allow_flight, flying, "
                        + "world, x, y, z, yaw, pitch, saved_at FROM spleef_vault WHERE uuid = ?",
                ItemVault::mapRecord, player.toString());
    }

    /** Everyone still owed their items, for {@code /spleef vault list}. */
    public List<String> pendingNames() {
        return storage.queryAll("SELECT player_name FROM spleef_vault ORDER BY player_name",
                rs -> rs.getString(1));
    }

    public int pendingCount() {
        return storage.queryOne("SELECT COUNT(*) FROM spleef_vault", rs -> rs.getInt(1)).orElse(0);
    }

    // --- session journal (crash detection) ---

    public void openSession(String sessionId, String arena) {
        storage.update("INSERT INTO spleef_session (id, arena, round, is_open, started_at) "
                        + "VALUES (?,?,?,?,?)",
                sessionId, arena, 1, 1, Instant.now().toString());
    }

    public void updateRound(String sessionId, int round) {
        storage.update("UPDATE spleef_session SET round = ? WHERE id = ?", round, sessionId);
    }

    public void closeSession(String sessionId) {
        storage.update("UPDATE spleef_session SET is_open = 0 WHERE id = ?", sessionId);
    }

    /**
     * Detects a session that never closed — the last run died mid-event.
     * Vault rows are deliberately left pending so every affected player is made
     * whole the next time they log in.
     */
    public void recoverOrphanedSessions() {
        List<String> orphans = storage.queryAll(
                "SELECT id FROM spleef_session WHERE is_open = 1", rs -> rs.getString(1));
        if (orphans.isEmpty()) {
            return;
        }
        int owed = pendingCount();
        logger.warn("Found {} spleef session(s) that did not shut down cleanly. "
                + "{} player(s) still have items vaulted — they will be restored automatically "
                + "on their next login. Use /spleef vault list to review.", orphans.size(), owed);
        for (String id : orphans) {
            closeSession(id);
        }
    }

    // --- helpers ---

    private static void clearInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(null);
        inventory.setItemInOffHand(null);
        player.setLevel(0);
        player.setExp(0f);
    }

    private static VaultRecord mapRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        String worldName = rs.getString("world");
        Location preGame = null;
        if (worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                preGame = new Location(world, rs.getDouble("x"), rs.getDouble("y"),
                        rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"));
            }
        }
        return new VaultRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getString("session_id"),
                ItemStack.deserializeItemsFromBytes(rs.getBytes("contents")),
                ItemStack.deserializeItemsFromBytes(rs.getBytes("armor")),
                ItemStack.deserializeItemsFromBytes(rs.getBytes("extra")),
                rs.getInt("xp_level"),
                rs.getFloat("xp_progress"),
                rs.getDouble("health"),
                rs.getInt("food"),
                rs.getFloat("saturation"),
                parseGameMode(rs.getString("game_mode")),
                rs.getInt("allow_flight") != 0,
                rs.getInt("flying") != 0,
                preGame,
                Instant.parse(rs.getString("saved_at")));
    }

    private static GameMode parseGameMode(@Nullable String name) {
        if (name == null) {
            return GameMode.SURVIVAL;
        }
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return GameMode.SURVIVAL;
        }
    }
}
