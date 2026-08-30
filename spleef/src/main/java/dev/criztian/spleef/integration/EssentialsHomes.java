package dev.criztian.spleef.integration;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * The only class in this plugin that names an EssentialsX type.
 *
 * <p>Kept package-private and separate on purpose: a server without EssentialsX
 * fails to verify <em>this</em> class and nothing else, so {@link HomeLocator}
 * catches the error and carries on. Same trick the framework's EconomyService
 * uses for Vault.</p>
 */
final class EssentialsHomes {

    private EssentialsHomes() {}

    static @Nullable Location firstHome(Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
        if (!(plugin instanceof Essentials essentials)) {
            return null;
        }
        User user = essentials.getUser(player);
        if (user == null) {
            return null;
        }
        List<String> homes = user.getHomes();
        if (homes == null || homes.isEmpty()) {
            return null;
        }
        // A home actually called "home" is what /home takes you to; otherwise
        // fall back to whichever the player set first.
        String name = homes.contains("home") ? "home" : homes.get(0);
        return user.getHome(name);
    }
}
