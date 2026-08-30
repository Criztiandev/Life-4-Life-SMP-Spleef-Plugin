package dev.criztian.spleef.arena.wand;

import dev.criztian.framework.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Turns wand clicks into corner selections. */
public final class WandListener implements Listener {

    private final WandItem wand;
    private final SelectionService selections;
    private final MessageService messages;

    public WandListener(WandItem wand, SelectionService selections, MessageService messages) {
        this.wand = wand;
        this.selections = selections;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        // Interact fires once per hand; without this the off-hand pass would
        // overwrite the corner we just set.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!wand.isWand(event.getItem()) || !player.hasPermission("spleef.admin")) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        Action action = event.getAction();
        Selection selection = selections.of(player.getUniqueId());
        String key;
        if (action == Action.LEFT_CLICK_BLOCK) {
            selection.first(block.getLocation());
            key = "wand.corner-1";
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            selection.second(block.getLocation());
            key = "wand.corner-2";
        } else {
            return;
        }

        event.setCancelled(true);
        messages.send(player, key,
                Placeholder.unparsed("x", String.valueOf(block.getX())),
                Placeholder.unparsed("y", String.valueOf(block.getY())),
                Placeholder.unparsed("z", String.valueOf(block.getZ())),
                Placeholder.unparsed("volume", selection.complete()
                        ? String.valueOf(selection.toCuboid().volume()) : "?"));
    }

    /**
     * In creative, a left-click destroys the block before the cancelled
     * PlayerInteractEvent can stop it. Cancelling the break too is what keeps
     * the wand from eating the arena.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        if (wand.isWand(event.getPlayer().getInventory().getItemInMainHand())
                && event.getPlayer().hasPermission("spleef.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.clear(event.getPlayer().getUniqueId());
    }
}
