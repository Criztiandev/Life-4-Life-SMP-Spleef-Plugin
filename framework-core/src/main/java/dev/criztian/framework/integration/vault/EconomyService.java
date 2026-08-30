package dev.criztian.framework.integration.vault;

import dev.criztian.framework.plugin.Service;
import java.math.BigDecimal;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Economy over VaultUnlocked ({@code net.milkbowl.vault2}), falling back to the
 * legacy Vault interface. Gracefully no-ops when no economy is installed —
 * check {@link #available()} for UX, though every method is safe to call.
 *
 * <p>This class names no Vault type: each provider lives in its own class file
 * ({@link ModernEconomyBridge}, {@link LegacyEconomyBridge}) so a server with
 * only one of the two APIs never fails class verification. The provider is
 * resolved lazily and re-checked while absent, so plugin load order doesn't
 * matter.</p>
 */
public final class EconomyService implements Service {

    private final JavaPlugin plugin;
    private volatile @Nullable EconomyBridge bridge;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return bridge() != null;
    }

    public BigDecimal balance(OfflinePlayer player) {
        EconomyBridge b = bridge();
        return b == null ? BigDecimal.ZERO : b.balance(player);
    }

    /** @return true if the full amount was withdrawn */
    public boolean withdraw(OfflinePlayer player, BigDecimal amount) {
        EconomyBridge b = bridge();
        return b != null && b.withdraw(player, amount);
    }

    /** @return true if the amount was deposited */
    public boolean deposit(OfflinePlayer player, BigDecimal amount) {
        EconomyBridge b = bridge();
        return b != null && b.deposit(player, amount);
    }

    public String format(BigDecimal amount) {
        EconomyBridge b = bridge();
        return b == null ? amount.toPlainString() : b.format(amount);
    }

    /** Forces re-resolution, e.g. after an economy plugin is reloaded. */
    public synchronized void refresh() {
        bridge = null;
    }

    private @Nullable EconomyBridge bridge() {
        EconomyBridge current = this.bridge;
        if (current != null) {
            return current;
        }
        // "Vault" is also the plugin name VaultUnlocked registers under.
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        synchronized (this) {
            if (this.bridge == null) {
                this.bridge = resolve();
            }
            return this.bridge;
        }
    }

    private @Nullable EconomyBridge resolve() {
        // NoClassDefFoundError: the installed Vault flavour lacks that API's
        // classes, so verifying the bridge class fails — try the other one.
        try {
            EconomyBridge modern = ModernEconomyBridge.tryCreate(plugin.getName());
            if (modern != null) {
                return modern;
            }
        } catch (NoClassDefFoundError legacyVaultOnly) {
            plugin.getSLF4JLogger().debug("VaultUnlocked API not present, trying legacy Vault");
        }
        try {
            return LegacyEconomyBridge.tryCreate();
        } catch (NoClassDefFoundError noVaultApiAtAll) {
            plugin.getSLF4JLogger().warn("'Vault' plugin present but exposes no known economy API");
            return null;
        }
    }
}
