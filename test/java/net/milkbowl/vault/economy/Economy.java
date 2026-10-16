package net.milkbowl.vault.economy;
import org.bukkit.OfflinePlayer;
/** Minimal test fixture for the locally verified legacy Vault API signatures; never packaged. */
public interface Economy {
    String getName(); int fractionalDigits(); double getBalance(OfflinePlayer player);
    EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);
    EconomyResponse depositPlayer(OfflinePlayer player, double amount);
}
