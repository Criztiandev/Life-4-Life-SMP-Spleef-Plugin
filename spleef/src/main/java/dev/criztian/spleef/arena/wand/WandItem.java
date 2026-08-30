package dev.criztian.spleef.arena.wand;

import dev.criztian.framework.item.ItemBuilder;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** The selection wand: a PDC-tagged item, so any material can serve as the wand. */
public final class WandItem {

    private final NamespacedKey key;

    public WandItem(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "wand");
    }

    public ItemStack create(Material material) {
        return ItemBuilder.of(material)
                .name("<aqua><bold>Spleef Wand</bold></aqua>")
                .lore(List.of(
                        "<gray>Left-click a block  <dark_gray>»</dark_gray> <white>corner 1</white></gray>",
                        "<gray>Right-click a block <dark_gray>»</dark_gray> <white>corner 2</white></gray>",
                        "",
                        "<dark_gray>/spleef area save <name></dark_gray>"))
                .glint(true)
                .pdc(key, PersistentDataType.BYTE, (byte) 1)
                .build();
    }

    public boolean isWand(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
