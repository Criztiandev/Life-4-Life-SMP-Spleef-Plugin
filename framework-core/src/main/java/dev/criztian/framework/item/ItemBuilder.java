package dev.criztian.framework.item;

import dev.criztian.framework.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Fluent builder for gameplay items (drops, rewards, kit contents). For GUI
 * icons inside menus, prefer InvUI's own ItemBuilder.
 *
 * <p>Name/lore default to non-italic — Minecraft italicizes custom names by
 * default, which is almost never wanted.</p>
 */
public final class ItemBuilder {

    private final ItemStack stack;

    private ItemBuilder(ItemStack stack) {
        this.stack = stack;
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(new ItemStack(material));
    }

    /** Starts from a copy — the passed stack is not mutated. */
    public static ItemBuilder of(ItemStack base) {
        return new ItemBuilder(base.clone());
    }

    public ItemBuilder amount(int amount) {
        stack.setAmount(amount);
        return this;
    }

    public ItemBuilder name(Component name) {
        return meta(m -> m.displayName(unitalic(name)));
    }

    public ItemBuilder name(String miniMessage) {
        return name(Text.mm(miniMessage));
    }

    public ItemBuilder lore(Component... lines) {
        return meta(m -> {
            List<Component> lore = new ArrayList<>(lines.length);
            for (Component line : lines) {
                lore.add(unitalic(line));
            }
            m.lore(lore);
        });
    }

    public ItemBuilder lore(List<String> miniMessageLines) {
        return meta(m -> {
            List<Component> lore = new ArrayList<>(miniMessageLines.size());
            for (String line : miniMessageLines) {
                lore.add(unitalic(Text.mm(line)));
            }
            m.lore(lore);
        });
    }

    /** Forces the enchantment glint on or off regardless of enchantments. */
    public ItemBuilder glint(boolean glint) {
        return meta(m -> m.setEnchantmentGlintOverride(glint));
    }

    /** Points the item at an {@code item_model} resource for custom textures. */
    public ItemBuilder model(NamespacedKey model) {
        return meta(m -> m.setItemModel(model));
    }

    /** Stores a value in the item's PersistentDataContainer. */
    public <P, C> ItemBuilder pdc(NamespacedKey key, PersistentDataType<P, C> type, C value) {
        return meta(m -> m.getPersistentDataContainer().set(key, type, value));
    }

    /** Escape hatch for anything not covered by the builder. */
    public ItemBuilder edit(Consumer<ItemStack> mutator) {
        mutator.accept(stack);
        return this;
    }

    public ItemStack build() {
        return stack;
    }

    private ItemBuilder meta(Consumer<ItemMeta> mutator) {
        stack.editMeta(mutator);
        return this;
    }

    private static Component unitalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
