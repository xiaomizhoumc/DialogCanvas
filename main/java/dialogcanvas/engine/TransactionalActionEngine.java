package dialogcanvas.engine;

import dialogcanvas.dialog.TextRenderer;
import dialogcanvas.engine.action.*;
import dialogcanvas.engine.condition.ConditionResult;
import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import io.papermc.paper.dialog.DialogResponseView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Per-player serial transactions: reversible state first, then ordered post-commit effects. */
public final class TransactionalActionEngine implements ActionEngine {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\(([A-Za-z0-9_.-]+)\\)");
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_.-]+)?");
    private final Scheduler scheduler;
    private final ActionServices services;
    private final Logger logger;
    private final DoubleSupplier random;
    private final dialogcanvas.engine.condition.PlaceholderResolver messagePlaceholders;
    private final JsonMapper mapper = MenuJson.createMapper();
    private final TextRenderer texts = new TextRenderer();
    private final Map<UUID, ArrayDeque<Run>> queues = new HashMap<>();
    private boolean stopping;
    private CompletableFuture<Void> shutdown;

    public TransactionalActionEngine(Scheduler scheduler, ActionServices services, Logger logger) {
        this(scheduler, services, logger, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    public TransactionalActionEngine(Scheduler scheduler, ActionServices services, Logger logger, DoubleSupplier random) {
        this(scheduler, services, logger, random, (player, token) -> Optional.empty());
    }

    public TransactionalActionEngine(Scheduler scheduler, ActionServices services, Logger logger, DoubleSupplier random,
                                    dialogcanvas.engine.condition.PlaceholderResolver messagePlaceholders) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.services = Objects.requireNonNull(services);
        this.logger = Objects.requireNonNull(logger);
        this.random = Objects.requireNonNull(random);
        this.messagePlaceholders = Objects.requireNonNull(messagePlaceholders);
    }

    @Override
    public void execute(Player player, MenuDefinition menu, ActionDefinition button, DialogResponseView response) {
        executeAsync(player, menu, button, response).whenComplete((ignored, failure) -> {
            if (failure != null) logger.warning("Action callback failed [ACTION_CALLBACK_FAILED]; inspect the transaction status without repeating the action");
        });
    }

    @Override
    public CompletableFuture<Void> executeAsync(Player player, MenuDefinition menu, ActionDefinition button, DialogResponseView response) {
        var answer = new CompletableFuture<Void>();
        scheduler.sync(() -> {
            Map<String, JsonNode> inputs = readInputs(menu, response);
            enqueue(player, menu, button, null, inputs).whenComplete((result, failure) -> {
                if (failure != null) answer.completeExceptionally(failure);
                else if (result.successful()) answer.complete(null);
                else answer.completeExceptionally(new IllegalStateException(result.status() + ": " + result.message()));
            });
        }).whenComplete((ignored, failure) -> { if (failure != null) answer.completeExceptionally(failure); });
        return answer.copy();
    }

    /** Direct entry for already validated server input; runs independently of a button condition gate. */
    public CompletableFuture<ActionChainResult> executeChain(Player player, MenuDefinition menu,
            List<ActionStepDefinition> actions, Map<String, JsonNode> inputs) {
        return executeChain(player, menu, actions, inputs, () -> CompletableFuture.completedFuture(null));
    }

    /** Persist quota/journal state after reversible actions, before messages/commands or any other effects. */
    public CompletableFuture<ActionChainResult> executeChain(Player player, MenuDefinition menu,
            List<ActionStepDefinition> actions, Map<String, JsonNode> inputs,
            Supplier<CompletableFuture<Void>> beforeEffects) {
        return executeChain(player, menu, actions, inputs, beforeEffects, null);
    }
    public CompletableFuture<ActionChainResult> executeChain(Player player, MenuDefinition menu,
            List<ActionStepDefinition> actions, Map<String, JsonNode> inputs,
            Supplier<CompletableFuture<Void>> beforeEffects, UUID transactionId) {
        var answer = new CompletableFuture<ActionChainResult>();
        scheduler.sync(() -> enqueue(player, menu, null, actions, inputs, beforeEffects, transactionId).whenComplete((result, failure) -> {
            if (failure != null) answer.completeExceptionally(failure); else answer.complete(result);
        })).whenComplete((ignored, failure) -> { if (failure != null) answer.completeExceptionally(failure); });
        return answer.copy();
    }

    /** Stop accepting work, cancel waits and compensate state before closing Scheduler. Never block the main thread. */
    public CompletableFuture<Void> shutdown() {
        var answer = new CompletableFuture<Void>();
        scheduler.sync(() -> {
            if (shutdown == null) shutdown = new CompletableFuture<>();
            shutdown.whenComplete((ignored, failure) -> {
                if (failure != null) answer.completeExceptionally(failure); else answer.complete(null);
            });
            stopping = true;
            for (var queue : List.copyOf(queues.values())) {
                for (Run run : List.copyOf(queue)) {
                    run.cancelled = true;
                    if (run == queue.peek() && !run.inFlight) {
                        if (run.timer != null) run.timer.cancel(false);
                        fail(run, new IllegalStateException("Action engine is stopping"));
                    }
                }
            }
            if (queues.isEmpty()) shutdown.complete(null);
        }).whenComplete((ignored, failure) -> { if (failure != null) answer.completeExceptionally(failure); });
        return answer.copy();
    }

    /** Final lifecycle cutoff. Pending providers cannot be awaited by onDisable.
     * Production's durable intent/reservation stays unresolved for manual recovery.
     * No provider or game operation may resume after this main-thread cutoff. */
    public void abandonPending() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Lifecycle cutoff must run on main");
        stopping = true;
        List<Run> pending = queues.values().stream().flatMap(Collection::stream).toList();
        queues.clear();
        for (Run run : pending) {
            run.done = true;
            run.cancelled = true;
            if (run.timer != null) { run.timer.cancel(false); run.timer = null; }
            logger.warning("Action transaction " + run.id + ": interrupted during shutdown; manual recovery required");
            run.result.complete(new ActionChainResult(run.id, ActionChainResult.Status.RECOVERY_REQUIRED,
                    "Plugin stopped before transaction settlement; manual recovery required"));
        }
        if (shutdown != null) shutdown.complete(null);
    }

    private CompletableFuture<ActionChainResult> enqueue(Player player, MenuDefinition menu, ActionDefinition button,
            List<ActionStepDefinition> actions, Map<String, JsonNode> inputs) {
        return enqueue(player, menu, button, actions, inputs, () -> CompletableFuture.completedFuture(null), null);
    }
    private CompletableFuture<ActionChainResult> enqueue(Player player, MenuDefinition menu, ActionDefinition button,
            List<ActionStepDefinition> actions, Map<String, JsonNode> inputs, Supplier<CompletableFuture<Void>> beforeEffects, UUID transactionId) {
        UUID owner = player.getUniqueId();
        var run = new Run(player, owner, mapper.readValue(mapper.writeValueAsString(menu), MenuDefinition.class),
                button == null ? null : mapper.readValue(mapper.writeValueAsString(button), ActionDefinition.class),
                actions == null ? null : copyActions(actions), copyInputs(inputs), transactionId);
        run.beforeEffects = Objects.requireNonNull(beforeEffects);
        if (stopping || !player.isOnline()) {
            run.result.complete(new ActionChainResult(run.id, ActionChainResult.Status.REJECTED, "Engine stopping or player offline"));
            return run.result;
        }
        var queue = queues.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
        queue.add(run);
        if (queue.size() == 1) start(run);
        return run.result;
    }

    private void start(Run run) {
        if (run.cancelled || stopping || !run.player.isOnline()) { fail(run, new IllegalStateException("Transaction cancelled")); return; }
        if (run.button == null || run.button.getConditions() == null) {
            plan(run, run.button == null ? run.requested : run.button.getActions());
            return;
        }
        run.inFlight = true;
        try {
            services.conditions().evaluate(run.player, run.menu, run.button, run.inputs).whenComplete((result, failure) -> hop(run, () -> {
                run.inFlight = false;
                if (failure != null) fail(run, failure);
                else if (run.cancelled || result == null || result.status() == ConditionResult.Status.ERROR) {
                    fail(run, new IllegalStateException("Condition evaluation failed or transaction cancelled"));
                } else plan(run, result.passed() ? run.button.getActions() : run.button.getDenyActions());
            }));
        } catch (RuntimeException failure) { run.inFlight = false; fail(run, failure); }
    }

    private void plan(Run run, List<ActionStepDefinition> actions) {
        try {
            List<ActionStepDefinition> values = actions == null ? List.of() : actions;
            if (values.size() > 256) throw new IllegalArgumentException("Maximum 256 actions per chain");
            var plans = new ArrayList<Plan>();
            for (int index = 0; index < values.size(); index++) {
                ActionStepDefinition value = values.get(index);
                double chance = value.getChance() == null ? 1 : value.getChance();
                double delay = value.getDelay() == null ? 0 : value.getDelay();
                if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("chance must be in 0..1");
                if (!Double.isFinite(delay) || delay < 0 || delay != Math.floor(delay) || delay > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("delay must be an integer tick count in 0..2147483647");
                }
                plans.add(new Plan(index, value.getType(), (long) delay, chance, reversible(value), prepare(run, value, index)));
            }
            run.plans = List.copyOf(plans);
            advance(run);
        } catch (RuntimeException failure) { fail(run, failure); }
    }

    private void advance(Run run) {
        if (run.done || run.rollingBack) return;
        try {
            while (true) {
                if (run.cancelled || stopping || !run.player.isOnline()) throw new IllegalStateException("Transaction cancelled or player offline");
                Plan next;
                if (!run.committed) {
                    if (run.index == run.plans.size()) { run.committed = true; continue; }
                    next = run.plans.get(run.index++);
                    if (next.chance == 0 || (next.chance < 1 && random.getAsDouble() >= next.chance)) continue;
                    if ("return".equals(next.type)) run.index = run.plans.size();
                    else if (!next.reversible) { run.effects.add(next); continue; }
                } else {
                    if (!run.checkpointStarted) {
                        run.checkpointStarted = true;
                        run.inFlight = true;
                        Objects.requireNonNull(run.beforeEffects.get()).whenComplete((ignored, failure) -> hop(run, () -> {
                            run.inFlight = false;
                            if (failure != null) fail(run, failure);
                            else { run.checkpointPassed = true; advance(run); }
                        }));
                        return;
                    }
                    if (run.effectIndex == run.effects.size()) {
                        finish(run, ActionChainResult.Status.COMMITTED, "Committed"); return;
                    }
                    next = run.effects.get(run.effectIndex++);
                }
                if (next.delay > 0) {
                    run.timer = scheduler.syncAfter(next.delay, () -> {
                        run.timer = null;
                        if (run.done || run.rollingBack) return;
                        if (run.cancelled || stopping || !run.player.isOnline()) fail(run, new IllegalStateException("Transaction cancelled"));
                        else executeStep(run, next, true);
                    });
                    run.timer.whenComplete((ignored, failure) -> {
                        if (failure != null) hop(run, () -> fail(run, failure));
                    });
                    return;
                }
                if (!executeStep(run, next, false)) return;
            }
        } catch (RuntimeException failure) { fail(run, failure); }
    }

    private boolean executeStep(Run run, Plan plan, boolean resume) {
        try {
            if (plan.reversible) run.executed.add(plan); // Include the failing action for partial inventory mutation recovery.
            CompletableFuture<Void> operation = Objects.requireNonNull(plan.action.execute());
            if (operation.isDone()) {
                operation.join();
                if ("return".equals(plan.type)) run.committed = true;
                if (resume) advance(run);
                return true;
            }
            run.inFlight = true;
            operation.whenComplete((ignored, failure) -> hop(run, () -> {
                run.inFlight = false;
                if (failure != null) fail(run, failure);
                else { if ("return".equals(plan.type)) run.committed = true; advance(run); }
            }));
        } catch (RuntimeException failure) { fail(run, failure); }
        return false;
    }

    private void fail(Run run, Throwable failure) {
        if (run.done || run.rollingBack || run.failing) return;
        run.failing = true;
        run.failure = failure;
        CompletableFuture<Void> timer = run.timer;
        run.timer = null;
        if (timer != null) timer.cancel(false);
        if (run.committed) {
            finish(run, run.checkpointPassed ? ActionChainResult.Status.POST_COMMIT_FAILED
                    : ActionChainResult.Status.RECOVERY_REQUIRED, failure.toString()); return;
        }
        run.rollingBack = true;
        rollback(run, run.executed.size() - 1);
    }

    private void rollback(Run run, int index) {
        while (index >= 0) {
            Plan plan = run.executed.get(index);
            int current = index--;
            try {
                CompletableFuture<Void> undo = Objects.requireNonNull(plan.action.rollback());
                if (undo.isDone()) { undo.join(); continue; }
                int next = index;
                undo.whenComplete((ignored, failure) -> hop(run, () -> {
                    if (failure != null) compensationFailure(run, plan, failure);
                    rollback(run, next);
                }));
                return;
            } catch (RuntimeException failure) { compensationFailure(run, run.executed.get(current), failure); }
        }
        if (run.compensations.isEmpty()) {
            finish(run, ActionChainResult.Status.ROLLED_BACK, run.failure.toString());
        } else persistRecovery(run);
    }

    private void compensationFailure(Run run, Plan plan, Throwable failure) {
        Object data;
        try { data = plan.action.recoveryData(); }
        catch (RuntimeException unavailable) { data = "Recovery data unavailable: " + unavailable; }
        run.compensations.add(new ActionServices.FailedCompensation(plan.index, plan.type, data, failure.toString()));
        logger.warning("Action rollback failed: transaction=" + run.id + ", action=" + plan.index);
    }

    private void persistRecovery(Run run) {
        try {
            var record = new ActionServices.RecoveryRecord(run.id, run.owner, run.menu.getId(), run.failure.toString(), List.copyOf(run.compensations));
            services.recovery().save(record).whenComplete((ignored, failure) -> hop(run, () -> finish(run,
                    failure == null ? ActionChainResult.Status.RECOVERY_REQUIRED : ActionChainResult.Status.RECOVERY_SAVE_FAILED,
                    failure == null ? run.failure.toString() : failure.toString())));
        } catch (RuntimeException failure) { finish(run, ActionChainResult.Status.RECOVERY_SAVE_FAILED, failure.toString()); }
    }

    private void finish(Run run, ActionChainResult.Status status, String message) {
        if (run.done) return;
        run.done = true;
        var queue = queues.get(run.owner);
        queue.remove(run);
        if (queue.isEmpty()) queues.remove(run.owner);
        ActionChainResult.FailureCode code = status == ActionChainResult.Status.COMMITTED
                ? ActionChainResult.FailureCode.NONE : ActionChainResult.FailureCode.UNKNOWN;
        var causes = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = run.failure; cause != null && causes.add(cause); cause = cause.getCause()) {
            if (cause instanceof InsufficientFundsException) { code = ActionChainResult.FailureCode.INSUFFICIENT_FUNDS; break; }
        }
        if (status != ActionChainResult.Status.COMMITTED) logger.warning("Action transaction " + run.id + ": " + status + " [code=" + code + "]");
        run.result.complete(new ActionChainResult(run.id, status, message, code));
        if (!queue.isEmpty()) start(queue.peek());
        if (stopping && queues.isEmpty() && shutdown != null) shutdown.complete(null);
    }

    private void hop(Run run, Runnable task) {
        scheduler.sync(() -> { if (!run.done) task.run(); }).whenComplete((ignored, failure) -> {
            if (failure != null && !run.result.isDone()) {
                // Lifecycle must await shutdown() before closing Scheduler; no unsafe off-thread rollback.
                logger.severe("Main-thread scheduler unavailable for transaction " + run.id + "; manual recovery required");
                run.result.completeExceptionally(failure);
            }
        });
    }

    private ExecutableAction prepare(Run run, ActionStepDefinition action, int actionIndex) {
        return switch (action) {
            case ActionStepDefinition.EconomyDeposit value -> {
                double amount = positive(value.getAmount(), run.inputs);
                yield change(() -> services.economy().deposit(run.owner, amount));
            }
            case ActionStepDefinition.EconomyWithdraw value -> {
                double amount = positive(value.getAmount(), run.inputs);
                yield change(() -> services.economy().withdraw(run.owner, amount));
            }
            case ActionStepDefinition.PlayerDataSet value -> {
                String key = required(value.getKey());
                JsonNode data = Objects.requireNonNull(value.getValue(), "value is required").deepCopy();
                yield change(() -> services.playerData().set(run.owner, key, data.deepCopy()));
            }
            case ActionStepDefinition.PlayerDataIncrement value -> {
                String key = required(value.getKey());
                double amount = NumericExpression.evaluate(value.getAmount(), run.inputs);
                yield change(() -> services.playerData().increment(run.owner, key, amount));
            }
            // Delivery follows vanilla item appearance. Legacy cosmetic glow remains readable
            // in the document, but cannot make an unenchanted purchase look enchanted.
            case ActionStepDefinition.GiveItem value -> inventory(run.player, new ActionItemFactory().create(value, true), count(value.getAmount(), run.inputs), true);
            case ActionStepDefinition.TakeItem value -> inventory(run.player, new ActionItemFactory().create(value, false), count(value.getAmount(), run.inputs), false);
            case ActionStepDefinition.Message value -> {
                var numericKeys = new HashSet<String>();
                run.inputs.forEach((key, input) -> { if (input.isNumber()) numericKeys.add(key); });
                ActionMessageTemplate.validate(value.getText(), numericKeys);
                // Evaluate arithmetic before any state mutation; PAPI is resolved after commit.
                var template = ActionMessageTemplate.resolve(value.getText(), run.inputs, java.util.function.Function.identity());
                yield effect(() -> {
                    var resolved = ActionMessageTemplate.resolve(template, Map.of(), token -> {
                        String location = "transaction=" + run.id + " actions[" + actionIndex + "].text";
                        var result = messagePlaceholders.resolveResult(run.player, token, location);
                        if (!result.success()) {
                            if (!result.warningLogged()) logger.warning("Action message placeholder resolution failed [" + result.diagnosticCode() + "] at " + location);
                            throw new IllegalArgumentException("Message placeholder failed: " + result.diagnosticCode());
                        }
                        return result.value();
                    });
                    run.player.sendMessage(texts.render(resolved));
                    return CompletableFuture.completedFuture(null);
                });
            }
            case ActionStepDefinition.PlaySound value -> {
                String sound = required(value.getSound());
                if (sound.matches("[A-Z0-9_]+")) sound = soundConstantKey(sound);
                if (org.bukkit.NamespacedKey.fromString(sound) == null) throw new IllegalArgumentException("Invalid sound key");
                String soundKey = sound;
                float volume = asFloat(value.getVolume() == null ? 1 : value.getVolume(), "volume");
                float pitch = asFloat(value.getPitch() == null ? 1 : value.getPitch(), "pitch");
                if (volume < 0 || pitch < 0) throw new IllegalArgumentException("Invalid sound volume/pitch");
                yield effect(() -> { run.player.playSound(run.player.getLocation(), soundKey, volume, pitch); return CompletableFuture.completedFuture(null); });
            }
            case ActionStepDefinition.Command value -> command(run, value);
            case ActionStepDefinition.OpenMenu value -> {
                String id = required(value.getMenuId());
                yield effect(() -> services.menus().open(run.player, id));
            }
            case ActionStepDefinition.CloseMenu ignored -> effect(() -> services.menus().close(run.player));
            case ActionStepDefinition.RefreshMenu ignored -> effect(() -> services.menus().refresh(run.player, run.menu));
            case ActionStepDefinition.Return ignored -> effect(() -> CompletableFuture.completedFuture(null));
            default -> throw new IllegalArgumentException("Unsupported action type");
        };
    }

    private ExecutableAction command(Run run, ActionStepDefinition.Command value) {
        String name = required(value.getCommand());
        if (!COMMAND.matcher(name).matches()) throw new IllegalArgumentException("Command name must be a literal lowercase identifier");
        String executor = value.getExecutor() == null ? "player" : value.getExecutor();
        if (!CommandArguments.EXECUTORS.contains(executor)) throw new IllegalArgumentException("Unknown command executor");
        var args = new ArrayList<String>();
        for (String token : value.getArguments() == null ? List.<String>of() : value.getArguments()) {
            if (!CommandArguments.literal(token)) throw new IllegalArgumentException("Unsafe command argument");
            token = token.replace("{player}", run.player.getName()).replace("{uuid}", run.owner.toString());
            var matcher = REFERENCE.matcher(token);
            var expanded = new StringBuilder();
            while (matcher.find()) {
                JsonNode input = run.inputs.get(matcher.group(1));
                if (input == null || input.isNull() || input.isObject() || input.isArray()) throw new IllegalArgumentException("Invalid command input");
                String replacement = input.isString() ? input.asString() : input.toString();
                if (!CommandArguments.input(replacement)) throw new IllegalArgumentException("Unsafe command input argument");
                matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(expanded);
            if (!CommandArguments.literal(expanded.toString())) throw new IllegalArgumentException("Unsafe command argument");
            args.add(expanded.toString());
        }
        List<String> arguments = List.copyOf(args);
        boolean console = "console".equals(executor);
        String commandLine = name + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
        if (commandLine.length() > 8192) throw new IllegalArgumentException("Command exceeds 8192 characters");
        return effect(() -> {
            // Target commands check the selected sender's current permissions themselves.
            boolean wasOp = run.player.isOp();
            boolean dispatched;
            try {
                if ("op".equals(executor) && !wasOp) run.player.setOp(true);
                dispatched = Bukkit.dispatchCommand(console ? Bukkit.getConsoleSender() : run.player, commandLine);
            } finally {
                // Includes exceptions from commands and permission checks. No delayed elevated work is scheduled.
                if ("op".equals(executor) && run.player.isOp() != wasOp) run.player.setOp(wasOp);
            }
            if (!dispatched) {
                return CompletableFuture.failedFuture(new IllegalStateException("Command dispatch failed"));
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private ExecutableAction change(Supplier<CompletableFuture<ActionServices.Change>> mutate) {
        return new ExecutableAction() {
            private volatile ActionServices.Change receipt;
            private volatile boolean missingReceipt;
            public CompletableFuture<Void> execute() {
                return mutate.get().thenAccept(value -> {
                    if (value == null) { missingReceipt = true; throw new IllegalStateException("Provider returned no compensation receipt"); }
                    receipt = value;
                });
            }
            public CompletableFuture<Void> rollback() {
                if (missingReceipt) return CompletableFuture.failedFuture(new IllegalStateException("Provider compensation receipt missing"));
                return receipt == null ? CompletableFuture.completedFuture(null) : receipt.rollback();
            }
            public Object recoveryData() { return receipt == null ? "No receipt available" : receipt.recoveryData(); }
        };
    }

    private ExecutableAction effect(Supplier<CompletableFuture<Void>> action) {
        return new ExecutableAction() {
            private boolean attempted;
            public CompletableFuture<Void> execute() { attempted = true; return action.get(); }
            public CompletableFuture<Void> rollback() {
                return attempted ? CompletableFuture.failedFuture(new UnsupportedOperationException("Post-commit side effects cannot be undone"))
                        : CompletableFuture.completedFuture(null);
            }
            public Object recoveryData() { return "Irreversible post-commit effect"; }
        };
    }

    public record SlotChange(int slot, ItemStack before, ItemStack after) {}
    private ExecutableAction inventory(Player player, ItemStack prototype, int amount, boolean give) {
        return new ExecutableAction() {
            private List<SlotChange> changes = List.of();
            private boolean attempted;
            public CompletableFuture<Void> execute() {
                ItemStack[] before = cloneItems(player.getInventory().getStorageContents());
                ItemStack[] after = cloneItems(before);
                int remaining = amount;
                for (int index = 0; index < after.length && remaining > 0; index++) {
                    ItemStack stack = after[index];
                    if (stack != null && !stack.getType().isAir() && stack.isSimilar(prototype)) {
                        int transferred = give ? Math.min(remaining, Math.max(0, stack.getMaxStackSize() - stack.getAmount()))
                                : Math.min(remaining, stack.getAmount());
                        stack.setAmount(stack.getAmount() + (give ? transferred : -transferred));
                        if (stack.getAmount() == 0) after[index] = null;
                        remaining -= transferred;
                    }
                }
                if (give) for (int index = 0; index < after.length && remaining > 0; index++) {
                    if (after[index] == null || after[index].getType().isAir()) {
                        ItemStack stack = prototype.clone();
                        int transferred = Math.min(remaining, stack.getMaxStackSize());
                        stack.setAmount(transferred); after[index] = stack; remaining -= transferred;
                    }
                }
                if (remaining != 0) throw new IllegalStateException(give ? "Insufficient inventory space" : "Insufficient matching items");
                var slots = new ArrayList<SlotChange>();
                for (int index = 0; index < before.length; index++) if (!Objects.equals(before[index], after[index])) {
                    slots.add(new SlotChange(index, before[index], after[index]));
                }
                changes = List.copyOf(slots); attempted = true;
                player.getInventory().setStorageContents(after);
                return CompletableFuture.completedFuture(null);
            }
            public CompletableFuture<Void> rollback() {
                if (!attempted) return CompletableFuture.completedFuture(null);
                // A disconnected Player inventory may already have been saved/unloaded.
                // Do not claim a mutation of that stale object restored durable state.
                if (!player.isOnline()) throw new IllegalStateException("Player offline; inventory compensation requires manual recovery");
                ItemStack[] current = cloneItems(player.getInventory().getStorageContents());
                for (SlotChange change : changes) {
                    if (!Objects.equals(current[change.slot], change.after) && !Objects.equals(current[change.slot], change.before)) {
                        throw new IllegalStateException("Inventory slot changed externally: " + change.slot);
                    }
                }
                for (SlotChange change : changes) current[change.slot] = change.before == null ? null : change.before.clone();
                player.getInventory().setStorageContents(current);
                return CompletableFuture.completedFuture(null);
            }
            public Object recoveryData() { return changes; }
        };
    }

    private static boolean reversible(ActionStepDefinition value) {
        return value instanceof ActionStepDefinition.EconomyDeposit || value instanceof ActionStepDefinition.EconomyWithdraw
                || value instanceof ActionStepDefinition.PlayerDataSet || value instanceof ActionStepDefinition.PlayerDataIncrement
                || value instanceof ActionStepDefinition.GiveItem || value instanceof ActionStepDefinition.TakeItem;
    }
    private static double positive(String text, Map<String, JsonNode> inputs) {
        double value = NumericExpression.evaluate(text, inputs);
        if (value <= 0) throw new IllegalArgumentException("Amount must be positive");
        return value;
    }
    private static int count(String text, Map<String, JsonNode> inputs) {
        double value = positive(text, inputs);
        if (value != Math.floor(value) || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Item amount must be an integer");
        return (int) value;
    }
    private static float asFloat(double value, String name) {
        if (!Double.isFinite(value) || !Float.isFinite((float) value)) throw new IllegalArgumentException("Invalid " + name);
        return (float) value;
    }
    /** Compatibility for saved Bukkit constant names; underscore replacement corrupts note_block keys. */
    private static String soundConstantKey(String name) {
        try {
            Object value = org.bukkit.Sound.class.getField(name).get(null);
            if (!(value instanceof org.bukkit.Sound sound)) throw new IllegalArgumentException("Invalid sound constant");
            return io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.SOUND_EVENT).getKeyOrThrow(sound).asString();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Unknown sound constant: " + name, failure);
        }
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required field is missing");
        return value;
    }
    private static ItemStack[] cloneItems(ItemStack[] items) {
        return Arrays.stream(items).map(value -> value == null ? null : value.clone()).toArray(ItemStack[]::new);
    }
    private List<ActionStepDefinition> copyActions(List<ActionStepDefinition> values) {
        return mapper.readValue(mapper.writeValueAsString(values), mapper.getTypeFactory().constructCollectionType(List.class, ActionStepDefinition.class));
    }
    private static Map<String, JsonNode> copyInputs(Map<String, JsonNode> values) {
        var copy = new HashMap<String, JsonNode>();
        values.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return Map.copyOf(copy);
    }
    public static Map<String, JsonNode> readInputs(MenuDefinition menu, DialogResponseView response) {
        var result = new HashMap<String, JsonNode>();
        for (InputDefinition input : menu.getInputs() == null ? List.<InputDefinition>of() : menu.getInputs()) {
            if (response == null) throw new IllegalArgumentException("Missing Dialog response");
            String key = required(input.getKey());
            JsonNode value;
            if (input instanceof InputDefinition.Bool) {
                Boolean raw = response.getBoolean(key);
                if (raw == null) throw new IllegalArgumentException("Missing boolean input");
                value = JsonNodeFactory.instance.booleanNode(raw);
            } else if (input instanceof InputDefinition.NumberRange range) {
                Float raw = response.getFloat(key);
                if (raw == null || !Float.isFinite(raw) || range.getStart() == null || range.getEnd() == null
                        || raw < range.getStart() || raw > range.getEnd()) throw new IllegalArgumentException("Invalid range input");
                if (range.getStep() != null) {
                    double steps = (raw - range.getStart()) / range.getStep();
                    if (!Double.isFinite(steps) || Math.abs(steps - Math.rint(steps)) > 0.0001) throw new IllegalArgumentException("Invalid range step");
                }
                value = JsonNodeFactory.instance.numberNode(raw.doubleValue());
            } else {
                String raw = response.getText(key);
                if (raw == null) throw new IllegalArgumentException("Missing text input");
                if (input instanceof InputDefinition.Text text && raw.length() > (text.getMaxLength() == null ? 32 : text.getMaxLength())) {
                    throw new IllegalArgumentException("Text input is too long");
                }
                if (input instanceof InputDefinition.Text text && Boolean.TRUE.equals(text.getNumeric())) {
                    double number;
                    try { number = Double.parseDouble(raw.trim()); }
                    catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid numeric text input", failure); }
                    if (!Double.isFinite(number)
                            || text.getMinimum() != null && number < text.getMinimum()
                            || text.getMaximum() != null && number > text.getMaximum()
                            || Boolean.TRUE.equals(text.getInteger()) && number != Math.floor(number)) {
                        throw new IllegalArgumentException("Numeric text input is outside its allowed range");
                    }
                    value = JsonNodeFactory.instance.numberNode(number);
                } else {
                    if (input instanceof InputDefinition.SingleOption option && (option.getOptions() == null
                            || option.getOptions().stream().noneMatch(entry -> raw.equals(entry.getId())))) throw new IllegalArgumentException("Unknown selected option");
                    value = JsonNodeFactory.instance.stringNode(raw);
                }
            }
            if (result.put(key, value) != null) throw new IllegalArgumentException("Duplicate input key");
        }
        return Map.copyOf(result);
    }

    private record Plan(int index, String type, long delay, double chance, boolean reversible, ExecutableAction action) {}
    private static final class Run {
        final UUID id, owner;
        final Player player;
        final MenuDefinition menu;
        final ActionDefinition button;
        final List<ActionStepDefinition> requested;
        final Map<String, JsonNode> inputs;
        final CompletableFuture<ActionChainResult> result = new CompletableFuture<>();
        final List<Plan> executed = new ArrayList<>(), effects = new ArrayList<>();
        final List<ActionServices.FailedCompensation> compensations = new ArrayList<>();
        List<Plan> plans = List.of();
        int index, effectIndex;
        boolean committed, rollingBack, done, inFlight, cancelled, failing;
        boolean checkpointStarted, checkpointPassed;
        Supplier<CompletableFuture<Void>> beforeEffects;
        Throwable failure;
        CompletableFuture<Void> timer;
        Run(Player player, UUID owner, MenuDefinition menu, ActionDefinition button, List<ActionStepDefinition> actions, Map<String, JsonNode> inputs, UUID transactionId) {
            this.id = transactionId == null ? UUID.randomUUID() : transactionId;
            this.player = player; this.owner = owner; this.menu = menu; this.button = button; this.requested = actions; this.inputs = inputs;
        }
    }
}
