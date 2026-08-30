package dev.criztian.spleef.player;

import dev.criztian.framework.item.ItemBuilder;
import dev.criztian.spleef.SpleefConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * The spleef kit. The shovel is PDC-tagged so it can be recognised and stripped
 * on the way out — nobody should keep one after the event, and none should ever
 * reach a restored inventory.
 */
public final class Loadout {

    private final NamespacedKey key;

    public Loadout(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "shovel");
    }

    public ItemStack shovel(SpleefConfig config) {
        Material material = Material.matchMaterial(config.shovel.material);
        if (material == null) {
            material = Material.GOLDEN_SHOVEL;
        }
        return ItemBuilder.of(material)
                .name(config.shovel.name)
                .lore(config.shovel.lore)
                .pdc(key, PersistentDataType.BYTE, (byte) 1)
                .edit(stack -> stack.editMeta(meta -> {
                    if (config.shovel.efficiency > 0) {
                        meta.addEnchant(Enchantment.EFFICIENCY, config.shovel.efficiency, true);
                    }
                    meta.setUnbreakable(config.shovel.unbreakable);
                }))
                .build();
    }

    public boolean isShovel(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Gives a fresh shovel in the first hotbar slot and selects it. */
    public void give(Player player, SpleefConfig config) {
        strip(player);
        player.getInventory().setItem(0, shovel(config));
        player.getInventory().setHeldItemSlot(0);
    }

    /** Removes every marked shovel. Called on elimination and before any restore. */
    public void strip(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            if (isShovel(contents[slot])) {
                contents[slot] = null;
                changed = true;
            }
        }
        if (changed) {
            player.getInventory().setContents(contents);
        }
    }
}
