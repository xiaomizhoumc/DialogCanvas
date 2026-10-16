package dialogcanvas.engine.limit;

import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.AtomicFiles;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

/** One instance owns playerdata. Never share this directory between service instances. */
public final class LimitService implements AutoCloseable {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    public enum Reset { DAILY, WEEKLY, MONTHLY, NEVER, TIMED }
    public enum Outcome { COMMITTED, ROLLED_BACK, UNKNOWN }
    public enum Status { EXECUTED, PERMISSION_DENIED, CONDITION_DENIED, LIMIT_REACHED,
        COOLDOWN_ACTIVE, RECOVERY_REQUIRED, EXECUTION_ROLLED_BACK }
    public record Result(Status status, long remaining, Instant cooldownUntil) { }
    /** maxCount=-1 disables quota. quantity counts purchased items, not clicks. */
    public record Policy(String key, long maxCount, long quantity, Reset reset,
                         long timedSeconds, long cooldownSeconds) {
        public Policy {
            Objects.requireNonNull(key); Objects.requireNonNull(reset);
            if (!key.matches("[A-Za-z0-9_.:/-]{1,200}") || maxCount < -1 || quantity <= 0
                    || cooldownSeconds < 0 || (reset == Reset.TIMED && timedSeconds <= 0))
                throw new IllegalArgumentException("Invalid limit policy");
        }
    }
    private final Path directory;
    private final AtomicFiles.Replacement replacement;
    private final Scheduler scheduler;
    private final Clock clock;
    private final ZoneId zone;
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, CompletableFuture<Void>> tails = new HashMap<>();
    private boolean closed;
    private CompletableFuture<Void> closing;

    public LimitService(Path pluginDirectory, Scheduler scheduler) {
        this(pluginDirectory, scheduler, Clock.systemUTC(), ZoneId.systemDefault());
    }
    public LimitService(Path pluginDirectory, Scheduler scheduler, Clock clock, ZoneId zone) {
        this(pluginDirectory, scheduler, clock, zone, AtomicFiles::write);
    }
    LimitService(Path pluginDirectory, Scheduler scheduler, Clock clock, ZoneId zone, AtomicFiles.Replacement replacement) {
        this.directory = pluginDirectory.toAbsolutePath().normalize().resolve("playerdata");
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock); this.zone = Objects.requireNonNull(zone);
        this.replacement = Objects.requireNonNull(replacement);
    }

    /** Gates and execution run on the main thread; file operations run on workers.
     * A committed transaction with failed post-commit messages is still COMMITTED.
     * Exception/UNKNOWN retains the durable reservation for administrator recovery. */
    public CompletableFuture<Result> execute(UUID playerId, Policy policy,
            Supplier<Boolean> permission, Supplier<Boolean> conditions,
            Supplier<CompletableFuture<Outcome>> execution) {
        return executeMany(playerId, List.of(policy), permission, conditions, execution);
    }
    /** All rule reservations and counters for one button share a single atomic player file write. */
    public synchronized CompletableFuture<Result> executeMany(UUID playerId, List<Policy> policies,
            Supplier<Boolean> permission, Supplier<Boolean> conditions,
            Supplier<CompletableFuture<Outcome>> execution) {
        Objects.requireNonNull(execution);
        return executeManyDurably(playerId, policies, permission, conditions, ignored -> execution.get());
    }

    /** Production invokes the checkpoint once after state commit, before irreversible success effects.
     * A failed settlement retains the on-disk reservation; it is never silently retried. */
    public synchronized CompletableFuture<Result> executeManyDurably(UUID playerId, List<Policy> policies,
            Supplier<Boolean> permission, Supplier<Boolean> conditions,
            Function<Supplier<CompletableFuture<Void>>, CompletableFuture<Outcome>> execution) {
        Objects.requireNonNull(playerId); policies = List.copyOf(policies);
        if (policies.isEmpty() || policies.stream().map(Policy::key).distinct().count() != policies.size())
            throw new IllegalArgumentException("Expected distinct nonempty policies");
        List<Policy> rules = policies;
        Objects.requireNonNull(permission); Objects.requireNonNull(conditions); Objects.requireNonNull(execution);
        if (closed) return CompletableFuture.failedFuture(new RejectedExecutionException("LimitService stopped"));
        CompletableFuture<Void> previous = tails.getOrDefault(playerId, CompletableFuture.completedFuture(null));
        CompletableFuture<Result> work = previous.handle((v, e) -> null)
            .thenCompose(v -> onMain(() -> !permission.get() ? Status.PERMISSION_DENIED
                    : !conditions.get() ? Status.CONDITION_DENIED : null))
            .thenCompose(denied -> denied != null ? CompletableFuture.completedFuture(new Result(denied, 0, null))
                    : worker(() -> reserve(playerId, rules)).thenCompose(check -> {
                        if (check.status() != Status.EXECUTED) return CompletableFuture.completedFuture(check);
                        var checkpoint = new AtomicReference<CompletableFuture<Result>>();
                        Supplier<CompletableFuture<Void>> commit = () -> {
                            synchronized (checkpoint) {
                                if (checkpoint.get() == null) checkpoint.set(worker(() -> finish(playerId, rules, Outcome.COMMITTED)));
                                return checkpoint.get().thenApply(ignored -> null);
                            }
                        };
                        return onMain(() -> execution.apply(commit)).thenCompose(f -> Objects.requireNonNull(f))
                            .handle((outcome, failure) -> failure != null || outcome == null ? Outcome.UNKNOWN : outcome)
                            .thenCompose(outcome -> checkpoint.get() != null ? checkpoint.get()
                                    : worker(() -> finish(playerId, rules, outcome)));
                    }));
        CompletableFuture<Void> tail = work.handle((v, e) -> null);
        tails.put(playerId, tail);
        tail.whenComplete((v, e) -> { synchronized (this) { tails.remove(playerId, tail); } });
        // Caller cancellation cannot release the queue while a purchase is still executing.
        return work.copy();
    }

    private Result reserve(UUID uuid, List<Policy> policies) throws Exception {
        Map<String, Object> root = read(uuid);
        Result answer = null;
        for (Policy policy : policies) {
        Map<String, Object> state = state(root, policy.key());
        Instant now = clock.instant();
        if (state.containsKey("pendingQuantity")) return result(Status.RECOVERY_REQUIRED, state, policy);
        long count = number(state, "count");
        Instant resetAt = instant(state, "resetAt");
        if (policy.reset() != Reset.NEVER && resetAt != null && !now.isBefore(resetAt)) {
            state.put("count", 0L); state.remove("resetAt"); count = 0;
        }
        if (policy.maxCount() >= 0 && (count > policy.maxCount() || policy.quantity() > policy.maxCount() - count))
            return result(Status.LIMIT_REACHED, state, policy);
        // Reject unrepresentable intervals before invoking an irreversible purchase.
        now.plusSeconds(policy.cooldownSeconds());
        if (policy.reset() != Reset.NEVER) nextReset(policy, now);
        Math.addExact(count, policy.quantity());
        }
        // Check every quota before checking any cooldown, then persist all reservations together.
        for (Policy policy : policies) {
            Map<String, Object> state = state(root, policy.key());
            Instant cooldown = instant(state, "cooldownUntil");
            if (cooldown != null && clock.instant().isBefore(cooldown)) return result(Status.COOLDOWN_ACTIVE, state, policy);
        }
        for (Policy policy : policies) {
            Map<String, Object> state = state(root, policy.key());
            state.put("pendingQuantity", policy.quantity()); state.put("pendingSince", clock.instant().toString());
            if (answer == null) answer = result(Status.EXECUTED, state, policy);
        }
        write(uuid, root);
        return answer;
    }

    private Result finish(UUID uuid, List<Policy> policies, Outcome outcome) throws Exception {
        Map<String, Object> root = read(uuid); Result answer = null;
        for (Policy policy : policies) {
        Map<String, Object> state = state(root, policy.key());
        if (number(state, "pendingQuantity") != policy.quantity())
            throw new IllegalStateException("Reservation was changed externally");
        if (outcome == Outcome.UNKNOWN) return result(Status.RECOVERY_REQUIRED, state, policy);
        if (outcome == Outcome.COMMITTED) {
            Instant now = clock.instant();
            // A delayed purchase belongs to the period in which it successfully commits.
            Instant oldReset = instant(state, "resetAt");
            if (policy.reset() != Reset.NEVER && oldReset != null && !now.isBefore(oldReset)) state.put("count", 0L);
            state.put("count", Math.addExact(number(state, "count"), policy.quantity()));
            if (policy.reset() == Reset.NEVER) state.remove("resetAt");
            else if (oldReset == null || !now.isBefore(oldReset)) state.put("resetAt", nextReset(policy, now).toString());
            state.put("cooldownUntil", now.plusSeconds(policy.cooldownSeconds()).toString());
            // Explicit retention evidence; legacy entries without this evidence stay untouched.
            state.put("resetMode", policy.reset().name());
            state.put("lastCommittedAt", now.toString());
        }
        state.remove("pendingQuantity"); state.remove("pendingSince");
        if (answer == null) answer = result(outcome == Outcome.COMMITTED ? Status.EXECUTED : Status.EXECUTION_ROLLED_BACK, state, policy);
        }
        write(uuid, root);
        return answer;
    }

    private Instant nextReset(Policy policy, Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return switch (policy.reset()) {
            case DAILY -> today.plusDays(1).atStartOfDay(zone).toInstant();
            case WEEKLY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant();
            case MONTHLY -> today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant();
            case TIMED -> now.plusSeconds(policy.timedSeconds());
            case NEVER -> throw new IllegalStateException("NEVER has no reset time");
        };
    }
    private static Result result(Status status, Map<String, Object> state, Policy policy) {
        return new Result(status, policy.maxCount() < 0 ? -1 : Math.max(0, policy.maxCount() - number(state, "count")),
                instant(state, "cooldownUntil"));
    }
    private static long number(Map<String, Object> state, String key) {
        Object value = state.get(key);
        if (value == null) return 0;
        if (!(value instanceof Number) || !value.toString().matches("[0-9]+"))
            throw new IllegalStateException("Invalid stored counter: " + key);
        return Long.parseLong(value.toString());
    }
    private static Instant instant(Map<String, Object> state, String key) {
        Object value = state.get(key); return value == null ? null : Instant.parse(value.toString());
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Map<String, Object> root, String key) {
        Object limits = root.computeIfAbsent("limits", k -> new LinkedHashMap<String, Object>());
        if (!(limits instanceof Map<?, ?>)) throw new IllegalStateException("Invalid limits file");
        Object value = ((Map<String, Object>) limits).computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Invalid limit entry");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> read(UUID uuid) throws Exception {
        AtomicFiles.createDirectories(directory);
        Path file = directory.resolve(uuid + ".yml");
        AtomicFiles.requireTarget(file);
        if (!Files.exists(file)) return new LinkedHashMap<>();
        if (Files.size(file) > MAX_BYTES) throw new IllegalStateException("Playerdata too large");
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        Object value = new Yaml(new SafeConstructor(options)).load(Files.readString(file, StandardCharsets.UTF_8));
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Invalid playerdata YAML: " + uuid);
        return (Map<String, Object>) map;
    }
    private void write(UUID uuid, Map<String, Object> data) throws Exception {
        byte[] bytes = new Yaml().dump(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Playerdata too large");
        AtomicFiles.createDirectories(directory);
        replacement.write(directory.resolve(uuid + ".yml"), bytes);
    }
    private <T> CompletableFuture<T> onMain(Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        scheduler.sync(() -> result.complete(supplier.get())).whenComplete((v, e) -> {
            if (e != null) result.completeExceptionally(e);
        });
        return result;
    }
    private <T> CompletableFuture<T> worker(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); } catch (Exception e) { throw new CompletionException(e); }
        }, io);
    }
    @FunctionalInterface public interface Maintenance<T> { T run(Path file) throws Exception; }
    /** Trusted maintenance shares the exact player queue; no cached YAML can later resurrect entries. */
    public synchronized <T> CompletableFuture<T> maintain(UUID id, Maintenance<T> operation) {
        Objects.requireNonNull(id); Objects.requireNonNull(operation);
        if (closed) return CompletableFuture.failedFuture(new RejectedExecutionException("LimitService stopped"));
        var previous = tails.getOrDefault(id, CompletableFuture.completedFuture(null));
        CompletableFuture<T> work = previous.handle((v, error) -> null).thenCompose(ignored -> worker(() -> {
            AtomicFiles.createDirectories(directory);
            Path file = directory.resolve(id + ".yml"); AtomicFiles.requireTarget(file);
            return operation.run(file);
        }));
        CompletableFuture<Void> tail = work.handle((v, error) -> null);
        tails.put(id, tail);
        tail.whenComplete((v, error) -> { synchronized (this) { tails.remove(id, tail); } });
        return work.copy();
    }
    /** Await this before closing Scheduler, without blocking the server main thread. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing.copy();
        closed = true;
        closing = CompletableFuture.allOf(tails.values().toArray(CompletableFuture[]::new))
                .thenRun(io::shutdown).thenRunAsync(() -> {
                    try { while (!io.awaitTermination(1, TimeUnit.SECONDS)) { /* drain file callbacks */ } }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new CompletionException(failure);
                    }
                });
        return closing.copy();
    }
    @Override public void close() { closeAsync(); }
}
