package dev.criztian.spleef.listener;

import dev.criztian.spleef.SpleefPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Join and quit.
 *
 * <p>The join hook is the recovery path for everything: a disconnect mid-round,
 * a server crash, a shutdown while items were vaulted. If the plugin still owes
 * a player their inventory, they get it back the moment they log in.</p>
 */
public final class ConnectionListener implements Listener {

    private final SpleefPlugin plugin;

    public ConnectionListener(SpleefPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // One tick later: the player is fully in the world, so inventory writes
        // and gamemode changes stick.
        plugin.scheduler().runOnLater(event.getPlayer(),
                () -> plugin.games().onJoin(event.getPlayer()), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.games().onQuit(event.getPlayer());
    }
}
