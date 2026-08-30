package dev.criztian.framework.gui;

import dev.criztian.framework.plugin.Service;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.xenondevs.invui.InvUI;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.window.Window;

/**
 * Thin InvUI bootstrap. Build GUIs with InvUI's own fluent API
 * ({@code Gui.builder()}, {@code Item.builder()}, InvUI's {@code ItemBuilder}
 * for icons) — this service only pins the owning plugin and opens windows.
 *
 * <p>InvUI is not thread-safe: build and open GUIs on the player's region
 * thread. Only {@code notifyWindows()} is safe from async contexts.</p>
 */
public final class GuiService implements Service {

    private final JavaPlugin plugin;

    public GuiService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        // Shaded InvUI usually infers the owning plugin from the classloader,
        // but pin it explicitly. Second call throws — tolerate for reloads.
        try {
            InvUI.getInstance().setPlugin(plugin);
        } catch (IllegalStateException alreadySet) {
            // already pinned (e.g. plugin reload within same classloader)
        }
    }

    /** Builds a single-viewer window over {@code gui} and opens it. */
    public void open(Player player, Gui gui, Component title) {
        Window.builder()
                .setTitle(title)
                .setUpperGui(gui)
                .open(player);
    }
}
