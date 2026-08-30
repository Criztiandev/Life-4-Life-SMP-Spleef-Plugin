package dev.criztian.framework.integration.vault;

import java.math.BigDecimal;
import org.bukkit.OfflinePlayer;

/**
 * Provider-agnostic economy operations. Names no Vault type, so
 * {@link EconomyService} can reference it safely without PlaceholderAPI-style
 * class-loading hazards.
 */
interface EconomyBridge {

    BigDecimal balance(OfflinePlayer player);

    boolean withdraw(OfflinePlayer player, BigDecimal amount);

    boolean deposit(OfflinePlayer player, BigDecimal amount);

    String format(BigDecimal amount);
}
