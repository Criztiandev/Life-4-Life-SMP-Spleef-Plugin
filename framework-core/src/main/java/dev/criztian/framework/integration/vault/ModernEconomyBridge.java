package dev.criztian.framework.integration.vault;

import java.math.BigDecimal;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

/**
 * VaultUnlocked ({@code net.milkbowl.vault2}) economy. In its own class file so
 * that servers running only legacy Vault never trigger verification of these
 * missing types.
 */
record ModernEconomyBridge(Economy economy, String pluginName) implements EconomyBridge {

    /** @return a bridge, or null if no vault2 provider is registered */
    static @Nullable EconomyBridge tryCreate(String pluginName) {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : new ModernEconomyBridge(registration.getProvider(), pluginName);
    }

    @Override
    public BigDecimal balance(OfflinePlayer player) {
        return economy.balance(pluginName, player.getUniqueId());
    }

    @Override
    public boolean withdraw(OfflinePlayer player, BigDecimal amount) {
        return economy.withdraw(pluginName, player.getUniqueId(), amount).transactionSuccess();
    }

    @Override
    public boolean deposit(OfflinePlayer player, BigDecimal amount) {
        return economy.deposit(pluginName, player.getUniqueId(), amount).transactionSuccess();
    }

    @Override
    public String format(BigDecimal amount) {
        return economy.format(pluginName, amount);
    }
}
