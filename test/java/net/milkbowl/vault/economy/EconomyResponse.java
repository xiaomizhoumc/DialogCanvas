package net.milkbowl.vault.economy;
public record EconomyResponse(boolean success) { public boolean transactionSuccess() { return success; } }
