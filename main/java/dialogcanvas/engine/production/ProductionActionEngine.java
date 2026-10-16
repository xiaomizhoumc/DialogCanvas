package dialogcanvas.engine.production;

import dialogcanvas.engine.*;
import dialogcanvas.engine.action.*;
import dialogcanvas.engine.condition.*;
import dialogcanvas.engine.limit.LimitService;
import dialogcanvas.model.*;
import dialogcanvas.feedback.ErrorFeedback;
import dialogcanvas.scheduler.Scheduler;
import io.papermc.paper.dialog.DialogResponseView;
import org.bukkit.entity.Player;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Main-thread conditions/actions, durable intent first, quota reservation before reversible state. */
public final class ProductionActionEngine implements ActionEngine, AutoCloseable {
    private final Scheduler scheduler;
    private final ProductionStore store;
    private final LimitService limits;
    private final ConditionEngine conditions;
    private final PlaceholderResolver placeholders;
    private final TransactionalActionEngine actions;
    private final ActionServices.Menus menus;
    private final Logger logger;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
    private final VaultEconomyAdapter economy;
    private volatile boolean stopping;
    private CompletableFuture<Void> closing;
    public ProductionActionEngine(Scheduler scheduler, ProductionStore store, LimitService limits,
            PlaceholderResolver placeholders, ActionServices.Menus menus, Logger logger) {
        this(scheduler, store, limits, placeholders, menus, logger, new VaultEconomyAdapter(store::markUncertain));
    }
    public ProductionActionEngine(Scheduler scheduler, ProductionStore store, LimitService limits,
            PlaceholderResolver placeholders, ActionServices.Menus menus, Logger logger, ActionServices.Economy money) {
        this.scheduler = scheduler; this.store = store; this.limits = limits; this.placeholders = placeholders; this.menus = menus; this.logger = logger;
        this.economy = money instanceof VaultEconomyAdapter vault ? vault : null;
        conditions = new ConditionEngine(logger);
        actions = new TransactionalActionEngine(scheduler, new ActionServices(money, store, menus, store::saveRecovery,
                (player, menu, button, inputs) -> CompletableFuture.completedFuture(conditions.evaluateDetailed(button.getConditions(), new ConditionContext(player, inputs, placeholders, store::find)))), logger,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(), placeholders);
    }
    public String economyName() { return economy == null ? "测试适配器" : economy.providerName(); }
    public boolean ready() { return store.ready() && !stopping; }
    @Override public void execute(Player player, MenuDefinition menu, ActionDefinition button, DialogResponseView response) { executeAsync(player, menu, button, response); }
    @Override public CompletableFuture<Void> executeAsync(Player player, MenuDefinition menu, ActionDefinition button, DialogResponseView response) {
        var result = new CompletableFuture<Void>();
        scheduler.sync(() -> {
            if ("close".equals(button.getActionType()) || "none".equals(button.getActionType())) { result.complete(null); return; }
            UUID id = player.getUniqueId();
            if (!player.isOnline()) { result.completeExceptionally(new IllegalStateException("Player offline")); return; }
            if (!ready()) { player.sendMessage("[DCMenu] 动作服务尚未就绪，请稍后重试。"); result.complete(null); return; }
            if (store.blocked(id)) { player.sendMessage("[DCMenu] 你有未完成交易，请服主核查交易记录后再试。"); result.complete(null); return; }
            if (!active.add(id)) { player.sendMessage("[DCMenu] 上一次操作正在处理，请稍候。"); result.complete(null); return; }
            Map<String, JsonNode> inputs;
            try { inputs = TransactionalActionEngine.readInputs(menu, response); }
            catch (RuntimeException failure) { active.remove(id); player.sendMessage("[DCMenu] 输入无效，请重新打开菜单填写。"); result.complete(null); return; }
            pending.add(result);
            result.whenComplete((ignored, failure) -> pending.remove(result));
            var transaction = new AtomicReference<UUID>();
            var conditionError = new AtomicReference<ConditionResult>();
            try {
            store.begin(id, MenuJson.createMapper().valueToTree(menu), button.getId(), inputs)
                .thenCompose(receipt -> { transaction.set(receipt); return onMain(() -> run(player, menu, button, inputs, receipt, conditionError)); })
                .thenCompose(future -> future)
                .thenCompose(status -> store.finish(id, transaction.get(), status, conditionError.get()))
                .whenComplete((ignored, failure) -> {
                    // Settlement owns only concurrent bookkeeping. It must finish even
                    // when shutdown has rejected all further Bukkit scheduling.
                    active.remove(id);
                    if (failure != null) {
                        logger.warning("菜单操作未完成，player=" + id + ", transaction=" + transaction.get() + ", code=PRODUCTION_OPERATION_FAILED；请核查交易状态，不要重复提交");
                        if (!stopping) scheduler.sync(() -> {
                            if (player.isOnline()) player.sendMessage("[DCMenu] " + ErrorFeedback.player("PRODUCTION_OPERATION_FAILED"));
                        });
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(null);
                    }
                });
            } catch (RuntimeException failure) {
                // Admission must not leak the player-wide gate if setup throws before a chain owns it.
                active.remove(id);
                result.completeExceptionally(failure);
            }
        }).whenComplete((nothing, failure) -> { if (failure != null) result.completeExceptionally(failure); });
        return result.copy();
    }
    private CompletableFuture<String> run(Player player, MenuDefinition menu, ActionDefinition button, Map<String, JsonNode> inputs, UUID transaction, AtomicReference<ConditionResult> conditionError) {
        UUID playerId = player.getUniqueId();
        if (stopping || !player.isOnline()) return CompletableFuture.completedFuture("denied");
        var checked = conditions.evaluateDetailed(button.getConditions(), new ConditionContext(player, inputs, placeholders, store::find), "transaction=" + transaction);
        if (checked.status() == ConditionResult.Status.ERROR) {
            conditionError.set(checked);
            player.sendMessage("[DCMenu] " + ErrorFeedback.player("CONDITION_ERROR"));
            return CompletableFuture.completedFuture("conditionError");
        }
        Supplier<CompletableFuture<Void>> journal = () -> store.uncertain(playerId)
                ? CompletableFuture.failedFuture(new IllegalStateException("Transaction state requires recovery"))
                : store.finish(playerId, transaction, "effectsPending");
        if (!checked.passed()) {
            if (list(button.getDenyActions()).isEmpty()) player.sendMessage("[DCMenu] " + ErrorFeedback.player("CONDITION_FAILED"));
            return actions.executeChain(player, menu, list(button.getDenyActions()), inputs, journal, transaction)
                    .thenCompose(value -> onMain(() -> reportResult(player, value)));
        }
        List<LimitService.Policy> rules;
        try { rules = policies(menu, button, inputs); }
        catch (RuntimeException invalid) {
            logger.warning("购买数量或限购配置无法计算，menu=" + menu.getId() + ", button=" + button.getId());
            player.sendMessage("[DCMenu] 所选数量无法用于本次购买，请检查数量和计算式；本次未扣款。");
            return CompletableFuture.completedFuture("denied");
        }
        var outcome = new AtomicReference<ActionChainResult>();
        java.util.function.Function<Supplier<CompletableFuture<Void>>, CompletableFuture<LimitService.Outcome>> execute = commit ->
            actions.executeChain(player, menu, list(button.getActions()), inputs, () -> store.uncertain(playerId)
                ? CompletableFuture.failedFuture(new IllegalStateException("Transaction state requires recovery"))
                : commit.get().thenCompose(ignored -> journal.get()), transaction).thenApply(value -> {
            outcome.set(value);
            return store.uncertain(playerId) ? LimitService.Outcome.UNKNOWN : value.stateCommitted() ? LimitService.Outcome.COMMITTED
                    : value.status() == ActionChainResult.Status.ROLLED_BACK || value.status() == ActionChainResult.Status.REJECTED ? LimitService.Outcome.ROLLED_BACK : LimitService.Outcome.UNKNOWN;
        });
        CompletableFuture<String> work;
        if (rules.isEmpty()) work = actions.executeChain(player, menu, list(button.getActions()), inputs, journal, transaction).thenApply(value -> { outcome.set(value); return status(value, playerId); });
        else work = limits.executeManyDurably(playerId, rules, () -> player.isOnline() && !stopping, () -> true, execute)
            .thenCompose(value -> onMain(() -> {
                switch (value.status()) {
                    case LIMIT_REACHED -> player.sendMessage("[DCMenu] 已达到限购数量；本周期还可购买 " + value.remaining() + " 件，本次未扣款。");
                    case COOLDOWN_ACTIVE -> player.sendMessage("[DCMenu] 操作太快，请在 " + value.cooldownUntil() + " 后再试，本次未扣款。");
                    case RECOVERY_REQUIRED -> player.sendMessage("[DCMenu] 交易计数待核查，请服主检查 playerdata 记录，不要重复购买。");
                    default -> {}
                }
                return value.status() == LimitService.Status.RECOVERY_REQUIRED ? "recoveryRequired" : outcome.get() == null ? "denied" : status(outcome.get(), playerId);
            }));
        return work.thenCompose(state -> onMain(() -> {
            ActionChainResult value = outcome.get();
            if (value != null) reportResult(player, value, "recoveryRequired".equals(state));
            return state;
        })).thenCompose(state -> {
            if (stopping || !"committed".equals(state) || !Boolean.TRUE.equals(button.getRefreshAfterAction())) return CompletableFuture.completedFuture(state);
            return onMain(() -> menus.refresh(player, menu)).thenCompose(f -> f).handle((ignored, failure) -> {
                if (failure != null) {
                    logger.warning("交易已完成，但菜单刷新失败，menu=" + menu.getId());
                    scheduler.sync(() -> { if (player.isOnline()) player.sendMessage("[DCMenu] 交易已完成，但菜单刷新失败；请重新打开，不要重复购买。"); });
                }
                return state;
            });
        });
    }
    /** Feedback only: never changes the transaction, quota outcome or durable receipt. Main thread. */
    private String reportResult(Player player, ActionChainResult value) {
        return reportResult(player, value, false);
    }
    private String reportResult(Player player, ActionChainResult value, boolean uncertain) {
        String state = status(value, player.getUniqueId());
        String code = ErrorFeedback.transactionCode(value, uncertain || "recoveryRequired".equals(state));
        if (!value.successful() || !"NONE".equals(code)) {
            logger.warning(ErrorFeedback.admin(code, value.transactionId()));
            if (!stopping && player.isOnline()) player.sendMessage("[DCMenu] " + ErrorFeedback.player(code));
        }
        return state;
    }
    private String status(ActionChainResult value, UUID player) {
        if (store.uncertain(player)) return "recoveryRequired";
        if (value.stateCommitted()) return "committed";
        return switch (value.status()) { case ROLLED_BACK, REJECTED -> "rolledBack"; default -> "recoveryRequired"; };
    }
    private static List<ActionStepDefinition> list(List<ActionStepDefinition> values) { return values == null ? List.of() : values; }
    private List<LimitService.Policy> policies(MenuDefinition menu, ActionDefinition button, Map<String, JsonNode> inputs) {
        Map<String, LimitDefinition> caps = button.getLimit() == null ? Map.of() : button.getLimit();
        Map<String, Double> cooldowns = button.getCooldown() == null ? Map.of() : button.getCooldown();
        if (caps.isEmpty() && cooldowns.isEmpty()) return List.of();
        long quantity = 0;
        for (ActionStepDefinition action : caps.isEmpty() ? List.<ActionStepDefinition>of() : list(button.getActions())) if (action instanceof ActionStepDefinition.GiveItem item) {
            double count = NumericExpression.evaluate(item.getAmount(), inputs);
            if (!Double.isFinite(count) || count <= 0 || count != Math.floor(count) || count > Integer.MAX_VALUE || (item.getChance() != null && item.getChance() != 1)) throw new IllegalArgumentException("Invalid limit purchase quantity");
            quantity = Math.addExact(quantity, (long) count);
        }
        if (quantity == 0) quantity = 1;
        Set<String> keys = new TreeSet<>(caps.keySet()); keys.addAll(cooldowns.keySet());
        var rules = new ArrayList<LimitService.Policy>();
        for (String key : keys) {
            LimitDefinition cap = caps.get(key); double seconds = cooldowns.getOrDefault(key, 0d);
            if (!Double.isFinite(seconds) || seconds < 0 || seconds != Math.floor(seconds) || seconds >= Long.MAX_VALUE) throw new IllegalArgumentException("Invalid cooldown");
            String scope = limitKey(menu.getId(), button.getId(), key, cap);
            rules.add(new LimitService.Policy(scope, cap == null || cap.getPlayer() == null ? -1 : cap.getPlayer(), quantity,
                    cap == null ? LimitService.Reset.NEVER : LimitService.Reset.valueOf(cap.getReset()),
                    cap == null || cap.getTimedSeconds() == null ? 0 : cap.getTimedSeconds(), (long) seconds));
        }
        return rules;
    }
    /** Shared pure identity for execution and retention policy verification. */
    public static String limitKey(String menuId, String buttonId, String key, LimitDefinition cap) {
        String identity = cap != null && cap.getScope() != null ? menuId + ":shared:" + cap.getScope() : menuId + ":" + buttonId + ":" + key;
        return "button:" + UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private <T> CompletableFuture<T> onMain(Supplier<T> task) {
        var answer = new CompletableFuture<T>(); scheduler.sync(() -> answer.complete(task.get())).whenComplete((ignored, failure) -> { if (failure != null) answer.completeExceptionally(failure); }); return answer;
    }
    public CompletableFuture<Void> closeAsync() {
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Action shutdown must run on main");
        if (closing != null) return closing.copy();
        stopping = true;
        actions.shutdown();
        actions.abandonPending();
        CompletableFuture<Void> limitsDone = limits.closeAsync();
        if (!active.isEmpty()) logger.warning("关闭时存在未完成交易；保留持久记录，重启后须核查，不会自动重试扣款。");
        closing = CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null).thenCombine(limitsDone, (a, b) -> (Void) null)
                .handle((ignored, failure) -> store.closeAsync().thenRun(() -> {
                    if (failure != null) throw new CompletionException(failure);
                })).thenCompose(future -> future);
        return closing.copy();
    }
    @Override public void close() { closeAsync(); }
}
