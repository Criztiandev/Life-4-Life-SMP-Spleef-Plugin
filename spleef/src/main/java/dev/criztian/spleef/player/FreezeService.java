package dev.criztian.spleef.player;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

/**
 * Holds players in place between {@code /spleef prepare} and {@code /spleef start}.
 *
 * <p><b>This is deliberately event-based, and must stay that way.</b> Every
 * alternative — a movement-speed or jump-strength attribute modifier, SLOWNESS
 * 255, {@code setWalkSpeed(0)}, {@code setInvulnerable(true)} — writes into
 * player NBT and survives a crash. If the server died mid-freeze with one of
 * those applied, every frozen player would come back permanently immobilised,
 * with no plugin running to undo it. Cancelling events leaves nothing behind:
 * kill the process at any moment and everyone is simply normal again.</p>
 *
 * <p>So: do not "optimise" this into an attribute modifier.</p>
 */
public final class FreezeService implements Listener {

    private final Map<UUID, Location> anchors = new ConcurrentHashMap<>();

    public void freeze(Player player) {
        anchors.put(player.getUniqueId(), player.getLocation().clone());
    }

    public void thaw(Player player) {
        anchors.remove(player.getUniqueId());
    }

    public void thawAll() {
        anchors.clear();
    }

    public boolean frozen(UUID player) {
        return anchors.containsKey(player);
    }

    public boolean any() {
        return !anchors.isEmpty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location anchor = anchors.get(event.getPlayer().getUniqueId());
        if (anchor == null || !event.hasChangedBlock()) {
            return;
        }
        // setTo rather than setCancelled: cancelling also reverts head rotation,
        // which makes the camera snap and feels broken. Keep their look, pin
        // their position.
        Location held = anchor.clone();
        held.setYaw(event.getTo().getYaw());
        held.setPitch(event.getTo().getPitch());
        event.setTo(held);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        if (frozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        if (frozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Frozen players are untouchable. Cancelling damage rather than setting
     * {@code invulnerable} keeps this residue-free, and leaves the plugin's own
     * elimination path free to act.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
