package dev.criztian.spleef.integration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Works out where a player goes when the event ends.
 *
 * <p>Chain: their EssentialsX home, then their bed or respawn anchor, then the
 * exact spot they stood on when {@code /spleef prepare} ran. The last step
 * matters — a player with no home should land back where they were, not at a
 * world spawn they never chose.</p>
 *
 * <p>Names no EssentialsX type; see {@link EssentialsHomes}.</p>
 */
public final class HomeLocator {

    private final Logger logger;
    private volatile boolean essentialsBroken;

    public HomeLocator(Logger logger) {
        this.logger = logger;
    }

    public boolean essentialsAvailable() {
        return !essentialsBroken && Bukkit.getPluginManager().getPlugin("Essentials") != null;
    }

    /**
     * @param preGame where the player was before the event, or null if unknown
     */
    public Location resolve(Player player, @Nullable Location preGame) {
        Location home = essentialsHome(player);
        if (home != null && home.getWorld() != null) {
            return home;
        }
        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null) {
            return respawn;
        }
        if (preGame != null && preGame.getWorld() != null) {
            return preGame;
        }
        return player.getWorld().getSpawnLocation();
    }

    private @Nullable Location essentialsHome(Player player) {
        if (!essentialsAvailable()) {
            return null;
        }
        try {
            return EssentialsHomes.firstHome(player);
        } catch (LinkageError e) {
            // Essentials is installed but not the flavour we compiled against.
            essentialsBroken = true;
            logger.warn("EssentialsX is present but its home API did not link; "
                    + "falling back to respawn point", e);
            return null;
        } catch (RuntimeException e) {
            logger.warn("Could not read {}'s EssentialsX home", player.getName(), e);
            return null;
        }
    }
}
