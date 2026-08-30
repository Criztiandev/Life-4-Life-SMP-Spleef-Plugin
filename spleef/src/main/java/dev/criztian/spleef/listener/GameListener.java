package dev.criztian.spleef.listener;

import dev.criztian.spleef.SpleefPlugin;
import dev.criztian.spleef.game.GameState;
import dev.criztian.spleef.game.Participant;
import dev.criztian.spleef.game.SpleefSession;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

/** Digging rules, arena protection, damage rules, and elimination. */
public final class GameListener implements Listener {

    private final SpleefPlugin plugin;
    private volatile @Nullable Set<Material> diggableCache;

    public GameListener(SpleefPlugin plugin) {
        this.plugin = plugin;
    }

    // --- digging ---

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        SpleefSession session = plugin.games().session();
        if (session == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean inArena = session.arena().region().contains(event.getBlock().getLocation());

        if (!session.isParticipant(player.getUniqueId())) {
            // Non-participants keep out of the arena; the rest of the world is theirs.
            if (inArena && !player.hasPermission("spleef.admin")) {
                event.setCancelled(true);
            }
            return;
        }
        Participant participant = session.participant(player.getUniqueId());
        if (!inArena || participant == null || participant.eliminated()
                || session.state() != GameState.RUNNING
                || !diggable().contains(event.getBlock().getType())) {
            event.setCancelled(true);
            return;
        }
        // No drops: a dug floor would otherwise become a carpet of snowballs.
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        SpleefSession session = plugin.games().session();
        if (session == null) {
            return;
        }
        if (session.arena().region().contains(event.getBlock().getLocation())
                && !event.getPlayer().hasPermission("spleef.admin")) {
            event.setCancelled(true);
        }
    }

    // --- damage rules ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!plugin.config().protections.noPvp) {
            return;
        }
        if (event.getEntity() instanceof Player victim
                && event.getDamager() instanceof Player
                && plugin.games().isParticipant(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (!plugin.config().protections.noFallDamage) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && event.getEntity() instanceof Player player
                && plugin.games().isParticipant(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!plugin.config().protections.noHunger) {
            return;
        }
        if (event.getEntity() instanceof Player player
                && plugin.games().isParticipant(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // --- keeping the shovel where it belongs ---

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.games().isParticipant(event.getPlayer().getUniqueId())
                && plugin.loadout().isShovel(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !plugin.games().isParticipant(player.getUniqueId())) {
            return;
        }
        if (plugin.loadout().isShovel(event.getCurrentItem())
                || plugin.loadout().isShovel(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    // --- elimination ---

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        SpleefSession session = plugin.games().session();
        if (session == null || !session.isParticipant(player.getUniqueId())) {
            return;
        }
        // The vault holds their real gear; nothing here may drop or be lost.
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setShowDeathMessages(false);
        event.deathMessage(null);

        plugin.games().eliminate(player, true);
        plugin.hud().eliminated(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        SpleefSession session = plugin.games().session();
        if (session == null || !session.isParticipant(player.getUniqueId())) {
            return;
        }
        event.setRespawnLocation(plugin.games().spectatorSpot());

        // Setting gamemode inside the respawn event does not stick — hop one
        // tick onto the player's own scheduler and do it there.
        plugin.scheduler().runOnLater(player, () -> {
            player.setGameMode(GameMode.SPECTATOR);
            player.teleportAsync(plugin.games().spectatorSpot(),
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            plugin.games().evaluate();
        }, null, 1);
    }

    // --- helpers ---

    /** Cached — this is read on every block break, so it must not rebuild a set each time. */
    private Set<Material> diggable() {
        Set<Material> cached = diggableCache;
        if (cached != null) {
            return cached;
        }
        Set<Material> materials = new HashSet<>();
        for (String name : plugin.config().arena.diggableMaterials) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material != null) {
                materials.add(material);
            } else {
                plugin.getSLF4JLogger().warn("Unknown diggable material in config: {}", name);
            }
        }
        diggableCache = Set.copyOf(materials);
        return diggableCache;
    }

    /** Called after a config reload so the cache picks up edits. */
    public void refreshDiggable() {
        diggableCache = null;
    }
}
