package dialogcanvas.engine.production;

import dialogcanvas.engine.action.ActionServices;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Optional Vault legacy API, also supplied by VaultUnlocked; never package someone else's API. */
public final class VaultEconomyAdapter implements ActionServices.Economy {
    private final Consumer<UUID> uncertain;
    public VaultEconomyAdapter(Consumer<UUID> uncertain) { this.uncertain = uncertain; }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Economy must run on main thread"); }
    private record Provider(Class<?> api, Object instance) {}
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Provider provider() throws ReflectiveOperationException {
        main();
        Class api = Class.forName("net.milkbowl.vault.economy.Economy");
        var registration = Bukkit.getServicesManager().getRegistration(api);
        if (registration == null || !registration.getPlugin().isEnabled()) throw new IllegalStateException("没有可用的 Vault 经济服务，请检查经济插件");
        return new Provider(api, registration.getProvider());
    }
    public String providerName() {
        try { var provider = provider(); return (String) provider.api.getMethod("getName").invoke(provider.instance); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return "未连接"; }
    }
    @Override public CompletableFuture<ActionServices.Change> deposit(UUID id, double amount) { return mutate(id, amount, true); }
    @Override public CompletableFuture<ActionServices.Change> withdraw(UUID id, double amount) { return mutate(id, amount, false); }
    private CompletableFuture<ActionServices.Change> mutate(UUID id, double amount, boolean deposit) {
        try {
            main(); Provider provider = provider();
            if (!Double.isFinite(amount) || amount <= 0) throw new IllegalArgumentException("金额必须大于 0");
            int digits = (int) provider.api.getMethod("fractionalDigits").invoke(provider.instance);
            if (digits >= 0 && BigDecimal.valueOf(amount).stripTrailingZeros().scale() > digits) throw new IllegalArgumentException("金额的小数位超过经济插件支持范围");
            String name = String.valueOf(provider.api.getMethod("getName").invoke(provider.instance));
            OfflinePlayer player = Bukkit.getOfflinePlayer(id);
            double before = balance(provider, player);
            if (!deposit && before < amount) throw new dialogcanvas.engine.action.InsufficientFundsException();
            apply(provider, player, amount, deposit, id, before);
            return CompletableFuture.completedFuture(new ActionServices.Change() {
                private boolean restored;
                public CompletableFuture<Void> rollback() {
                    try {
                        main(); if (restored) return CompletableFuture.completedFuture(null);
                        if (provider().instance != provider.instance) throw new IllegalStateException("经济服务发生变化，不能自动补偿");
                        apply(provider, player, amount, !deposit, id, balance(provider, player));
                        restored = true; return CompletableFuture.completedFuture(null);
                    } catch (Exception | LinkageError failure) { uncertain.accept(id); return CompletableFuture.failedFuture(failure); }
                }
                public Object recoveryData() { return Map.of("playerId", id.toString(), "provider", name, "amount", amount, "operation", deposit ? "deposit" : "withdraw", "compensation", deposit ? "withdraw" : "deposit"); }
            });
        } catch (Exception | LinkageError failure) { return CompletableFuture.failedFuture(failure); }
    }
    private double balance(Provider provider, OfflinePlayer player) throws ReflectiveOperationException {
        double value = (double) provider.api.getMethod("getBalance", OfflinePlayer.class).invoke(provider.instance, player);
        if (!Double.isFinite(value)) throw new IllegalStateException("经济插件返回无效余额"); return value;
    }
    private void apply(Provider provider, OfflinePlayer player, double amount, boolean deposit, UUID id, double before) throws Exception {
        Object response;
        try { response = provider.api.getMethod(deposit ? "depositPlayer" : "withdrawPlayer", OfflinePlayer.class, double.class).invoke(provider.instance, player, amount); }
        catch (ReflectiveOperationException | RuntimeException failure) { uncertain.accept(id); throw failure; }
        try {
            if (response == null) { uncertain.accept(id); throw new IllegalStateException("经济插件未返回交易结果"); }
            boolean success = (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
            double after = balance(provider, player);
            double expected = success ? before + (deposit ? amount : -amount) : before;
            if (Math.abs(after - expected) > Math.max(0.000001, Math.ulp(expected) * 4)) {
                uncertain.accept(id); throw new IllegalStateException("经济余额变化与交易结果不一致，需要核查");
            }
            if (!success) throw new RejectedEconomyChange();
        } catch (RejectedEconomyChange rejection) { throw rejection; }
        catch (ReflectiveOperationException | RuntimeException failure) { uncertain.accept(id); throw failure; }
    }
    private static final class RejectedEconomyChange extends IllegalStateException {
        RejectedEconomyChange() { super("经济插件拒绝交易，未改变余额"); }
    }
}
