package dev.criztian.framework.integration.vault;

import java.math.BigDecimal;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

/**
 * Original MilkBowl Vault economy (double-based), used when no VaultUnlocked
 * provider is registered. Isolated in its own class file for the same
 * class-loading reason as {@link ModernEconomyBridge}.
 */
@SuppressWarnings("deprecation") // legacy Vault API is deprecated by design
record LegacyEconomyBridge(Economy economy) implements EconomyBridge {

    /** @return a bridge, or null if no legacy provider is registered */
    static @Nullable EconomyBridge tryCreate() {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : new LegacyEconomyBridge(registration.getProvider());
    }

    @Override
    public BigDecimal balance(OfflinePlayer player) {
        return BigDecimal.valueOf(economy.getBalance(player));
    }

    @Override
    public boolean withdraw(OfflinePlayer player, BigDecimal amount) {
        return economy.withdrawPlayer(player, amount.doubleValue()).transactionSuccess();
    }

    @Override
    public boolean deposit(OfflinePlayer player, BigDecimal amount) {
        return economy.depositPlayer(player, amount.doubleValue()).transactionSuccess();
    }

    @Override
    public String format(BigDecimal amount) {
        return economy.format(amount.doubleValue());
    }
}
