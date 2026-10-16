package dialogcanvas.engine.production;

import dialogcanvas.engine.TransactionalActionEngine;
import dialogcanvas.engine.action.ActionServices;
import dialogcanvas.engine.condition.ConditionResult;
import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.AtomicFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.bukkit.Bukkit;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One serial file worker; immutable cached player snapshots; journal intents survive interruption. */
public final class ProductionStore implements ActionServices.PlayerData, AutoCloseable {
    private static final Set<String> TERMINAL = Set.of("committed", "rolledBack", "denied", "conditionError");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final Path root;
    private final AtomicFiles.Replacement replacement;
    private final JsonMapper json = MenuJson.createMapper();
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("DCMenu-transactions").factory());
    private final Map<UUID, ObjectNode> players = new ConcurrentHashMap<>();
    private final Set<UUID> blocked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> uncertain = ConcurrentHashMap.newKeySet();
    private final Set<UUID> maintenance = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> maintenanceTasks = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;
    private volatile boolean closed;
    private CompletableFuture<Void> closing;
    public ProductionStore(Path root) { this(root, AtomicFiles::write); }
    ProductionStore(Path root, AtomicFiles.Replacement replacement) {
        this.root = root.toAbsolutePath().normalize(); this.replacement = Objects.requireNonNull(replacement);
    }
    public boolean ready() { return ready && !closed; }
    public boolean blocked(UUID id) { return blocked.contains(id); }
    public boolean uncertain(UUID id) { return uncertain.contains(id); }
    public void markUncertain(UUID id) { uncertain.add(id); }
    public CompletableFuture<Void> initialize() {
        return work(() -> {
            for (String name : List.of("player-data", "transactions", "recovery")) {
                AtomicFiles.createDirectories(root.resolve(name));
            }
            try (var stream = Files.list(root.resolve("transactions"))) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".json")).toList()) {
                    JsonNode value = read(file);
                    String status = value.path("status").asString();
                    if (!TERMINAL.contains(status)) blocked.add(UUID.fromString(value.path("playerId").asString()));
                }
            }
            if (closed) throw new RejectedExecutionException("Production store stopped during initialization");
            ready = true; return null;
        });
    }
    public Optional<JsonNode> find(UUID id, String key) {
        ObjectNode value = players.get(id);
        return value == null || !value.has(key) ? Optional.empty() : Optional.of(value.get(key).deepCopy());
    }
    public CompletableFuture<UUID> begin(UUID id, JsonNode menu, String button, Map<String, JsonNode> inputs) {
        return work(() -> {
            if (!ready || blocked.contains(id) || maintenance.contains(id)) throw new IllegalStateException("玩家有未完成交易或数据维护，请稍后重试并核查记录");
            Path file = path("player-data", id);
            ObjectNode data = Files.exists(file) ? (ObjectNode) read(file) : json.createObjectNode();
            players.put(id, data.deepCopy());
            UUID transaction = UUID.randomUUID();
            ObjectNode record = json.createObjectNode().put("transactionId", transaction.toString()).put("playerId", id.toString())
                    .put("status", "pending").put("created", java.time.Instant.now().toString()).put("buttonId", button);
            record.set("menu", menu); record.set("inputs", json.valueToTree(inputs));
            write(path("transactions", transaction), record);
            blocked.add(id); return transaction;
        });
    }
    public CompletableFuture<Void> finish(UUID id, UUID transaction, String status) {
        return finish(id, transaction, status, null);
    }
    public CompletableFuture<Void> finish(UUID id, UUID transaction, String status, ConditionResult conditionError) {
        return work(() -> {
            Path file = path("transactions", transaction); ObjectNode value = (ObjectNode) read(file);
            String outcome = uncertain.contains(id) ? "recoveryRequired" : status;
            value.put("status", outcome);
            if (conditionError != null && conditionError.status() == ConditionResult.Status.ERROR) {
                var detail = json.createObjectNode();
                detail.put("code", conditionError.errorCode()); detail.put("path", conditionError.path());
                value.set("conditionError", detail);
            }
            if (outcome.equals("effectsPending")) value.put("stateCommittedAt", java.time.Instant.now().toString());
            else value.put("finished", java.time.Instant.now().toString());
            write(file, value);
            if (TERMINAL.contains(outcome)) blocked.remove(id);
            return null;
        });
    }
    @Override public CompletableFuture<ActionServices.Change> set(UUID id, String key, JsonNode value) { return mutate(id, key, value.deepCopy(), null); }
    @Override public CompletableFuture<ActionServices.Change> increment(UUID id, String key, double amount) { return mutate(id, key, null, amount); }
    private CompletableFuture<ActionServices.Change> mutate(UUID id, String key, JsonNode value, Double increment) {
        return work(() -> {
            if (key == null || !key.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid player data key");
            ObjectNode current = Objects.requireNonNull(players.get(id), "Player data not loaded");
            JsonNode before = current.get(key); ObjectNode next = current.deepCopy(); JsonNode after = value;
            if (increment != null) {
                double old = before == null ? 0 : before.asDouble(Double.NaN);
                double number = old + increment;
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Player data is not a finite number");
                after = json.getNodeFactory().numberNode(number);
            }
            next.set(key, after); write(path("player-data", id), next); players.put(id, next);
            JsonNode changed = after.deepCopy();
            return new ActionServices.Change() {
                public CompletableFuture<Void> rollback() { return work(() -> {
                    ObjectNode latest = players.get(id).deepCopy();
                    if (!Objects.equals(latest.get(key), changed)) throw new IllegalStateException("Player data changed externally");
                    if (before == null) latest.remove(key); else latest.set(key, before);
                    write(path("player-data", id), latest); players.put(id, latest); return null;
                }); }
                public Object recoveryData() { return Map.of("playerId", id.toString(), "key", key, "before", before == null ? json.getNodeFactory().nullNode() : before, "after", changed); }
            };
        });
    }
    /** Build Bukkit inventory recovery bytes on main; only write the resulting JSON on workers. */
    public CompletableFuture<Void> saveRecovery(ActionServices.RecoveryRecord record) {
        if (!Bukkit.isPrimaryThread()) return CompletableFuture.failedFuture(new IllegalStateException("Recovery capture must run on main"));
        ObjectNode value = json.createObjectNode().put("transactionId", record.transactionId().toString()).put("playerId", record.playerId().toString())
                .put("menuId", record.menuId()).put("error", record.executionFailure());
        var failures = value.putArray("failures");
        for (var failed : record.failures()) {
            ObjectNode item = failures.addObject().put("actionIndex", failed.actionIndex()).put("actionType", failed.actionType()).put("error", failed.failure());
            if (failed.recoveryData() instanceof List<?> slots && slots.stream().allMatch(s -> s instanceof TransactionalActionEngine.SlotChange)) {
                var inventory = item.putArray("inventory");
                for (Object slot : slots) {
                    var change = (TransactionalActionEngine.SlotChange) slot;
                    var entry = inventory.addObject().put("slot", change.slot());
                    if (change.before() != null) entry.put("before", Base64.getEncoder().encodeToString(change.before().serializeAsBytes()));
                    if (change.after() != null) entry.put("after", Base64.getEncoder().encodeToString(change.after().serializeAsBytes()));
                }
            } else item.set("data", json.valueToTree(failed.recoveryData()));
        }
        return work(() -> { write(path("recovery", record.transactionId()), value); return null; });
    }
    private Path path(String folder, UUID id) throws java.io.IOException {
        Path path = root.resolve(folder).resolve(id + ".json"); AtomicFiles.requireTarget(path); return path;
    }
    public static final class MaintenanceDenied extends IllegalStateException {
        private final String code;
        public MaintenanceDenied(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    /** Serial admission with begin; hold until the real maintenance future settles, not its caller copy. */
    public synchronized <T> CompletableFuture<T> withPlayerMaintenance(UUID id, Supplier<CompletableFuture<T>> operation) {
        if (closed) return CompletableFuture.failedFuture(new MaintenanceDenied("STORE_NOT_READY"));
        CompletableFuture<T> actual = work(() -> {
            if (!ready()) throw new MaintenanceDenied("STORE_NOT_READY");
            if (blocked.contains(id) || uncertain.contains(id)) throw new MaintenanceDenied("TRANSACTION_UNRESOLVED");
            if (!maintenance.add(id)) throw new MaintenanceDenied("MAINTENANCE_BUSY");
            return id;
        }).thenCompose(ignored -> {
            // Journal inspection can be large: keep it off the transaction write worker.
            // The admission gate is already held, so this player's begin cannot race it.
            return CompletableFuture.supplyAsync(() -> {
                try { checkRetentionRecords(id); return Objects.requireNonNull(operation.get()); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }, command -> Thread.ofVirtual().name("DCMenu-retention-check").start(command))
                    .thenCompose(future -> future).whenComplete((value, error) -> maintenance.remove(id));
        });
        maintenanceTasks.add(actual);
        actual.whenComplete((value, error) -> maintenanceTasks.remove(actual));
        return actual.copy();
    }
    /** Fresh persistent check under the admission gate; no journal or recovery record is removed. */
    public void recheckPlayerMaintenance(UUID id) throws Exception {
        if (!ready() || !maintenance.contains(id)) throw new MaintenanceDenied("STORE_NOT_READY");
        if (blocked.contains(id) || uncertain.contains(id)) throw new MaintenanceDenied("TRANSACTION_UNRESOLVED");
        checkRetentionRecords(id);
    }
    private void checkRetentionRecords(UUID player) throws Exception {
        for (String folder : List.of("transactions", "recovery")) {
            Path directory = root.resolve(folder); AtomicFiles.requireDirectory(directory);
            try (var files = Files.newDirectoryStream(directory)) {
                int batch = 0;
                for (Path file : files) {
                    if (closed) throw new MaintenanceDenied("STORE_NOT_READY");
                    try {
                        String name = file.getFileName().toString();
                        if (!name.matches("[0-9a-f-]{36}\\.json")) throw new IllegalArgumentException();
                        UUID transaction = UUID.fromString(name.substring(0, 36));
                        JsonNode record = read(file);
                        if (!record.path("transactionId").isString() || !transaction.toString().equals(record.path("transactionId").asString())) throw new IllegalArgumentException();
                        String raw = record.path("playerId").asString(); UUID owner = UUID.fromString(raw);
                        if (!owner.toString().equals(raw)) throw new IllegalArgumentException();
                        if (folder.equals("transactions")) {
                            java.time.Instant created = java.time.Instant.parse(record.path("created").asString());
                            if (!record.path("status").isString()) throw new IllegalArgumentException();
                            if (TERMINAL.contains(record.path("status").asString())
                                    && java.time.Instant.parse(record.path("finished").asString()).isBefore(created)) throw new IllegalArgumentException();
                        }
                        if (owner.equals(player)) {
                            if (folder.equals("recovery")) throw new MaintenanceDenied("RECOVERY_RECORD_PRESENT");
                            if (!TERMINAL.contains(record.path("status").asString())) throw new MaintenanceDenied("TRANSACTION_UNRESOLVED");
                        }
                    } catch (MaintenanceDenied denied) { throw denied; }
                    catch (Exception invalid) { throw new MaintenanceDenied("UNRECOGNIZED_TRANSACTION_RECORD"); }
                    if (++batch == 200) { batch = 0; java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1)); }
                }
            }
        }
    }
    private JsonNode read(Path file) throws Exception {
        AtomicFiles.requireTarget(file);
        if (Files.size(file) > MAX_BYTES) throw new IllegalStateException("Unsafe production data");
        JsonNode value = json.readTree(Files.readAllBytes(file)); if (!value.isObject()) throw new IllegalStateException("Invalid production data"); return value;
    }
    private void write(Path path, JsonNode data) throws Exception {
        byte[] bytes = json.writeValueAsBytes(data);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Production data exceeds reload size limit");
        replacement.write(path, bytes);
    }
    private <T> CompletableFuture<T> work(Callable<T> task) {
        try { return CompletableFuture.supplyAsync(() -> { try { return task.call(); } catch (Exception failure) { throw new CompletionException(failure); } }, io); }
        catch (RejectedExecutionException failure) { return CompletableFuture.failedFuture(failure); }
    }
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing.copy();
        closed = true; ready = false; io.shutdown();
        closing = new CompletableFuture<>();
        Thread.ofVirtual().name("DCMenu-transactions-shutdown").start(() -> {
            try {
                while (!io.awaitTermination(1, TimeUnit.SECONDS)) { /* drain accepted writes */ }
                CompletableFuture.allOf(maintenanceTasks.toArray(CompletableFuture[]::new)).handle((value, failure) -> null).join();
                closing.complete(null);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); closing.completeExceptionally(failure);
            }
        });
        return closing.copy();
    }
    @Override public void close() { closeAsync(); }
}
