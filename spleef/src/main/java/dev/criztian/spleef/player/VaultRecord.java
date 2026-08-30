package dev.criztian.spleef.player;

import java.time.Instant;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A player's whole pre-game state, held by the plugin for the duration of an event.
 *
 * <p>The ender chest is deliberately absent: the game never touches it, so
 * taking custody of it would add risk without buying anything.</p>
 *
 * @param preGame where the player stood when the event started — the last
 *                fallback of the end-of-event teleport chain
 */
public record VaultRecord(UUID player, String playerName, String sessionId,
                          ItemStack[] contents, ItemStack[] armor, ItemStack[] extra,
                          int xpLevel, float xpProgress,
                          double health, int food, float saturation,
                          GameMode gameMode, boolean allowFlight, boolean flying,
                          @Nullable Location preGame, Instant savedAt) {
}
