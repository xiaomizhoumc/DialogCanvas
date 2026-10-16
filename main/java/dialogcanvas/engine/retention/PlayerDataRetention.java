package dialogcanvas.engine.retention;

import dialogcanvas.engine.limit.LimitService;
import dialogcanvas.engine.production.ProductionStore;
import dialogcanvas.storage.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

/** Background-only orchestration. Never touches Bukkit state, business JSON, or cached player values. */
public final class PlayerDataRetention implements AutoCloseable {
    private dialogcanvas.audit.AuditLogger audit = dialogcanvas.audit.AuditLogger.disabled();
    public void auditLogger(dialogcanvas.audit.AuditLogger audit) { this.audit = Objects.requireNonNull(audit); }
    @FunctionalInterface public interface CheckedWork { void run() throws Exception; }
    @FunctionalInterface public interface PolicyGuard { CompletableFuture<Void> protect(Map<String, String> modes, CheckedWork work); }
    private volatile PolicyGuard policyGuard = (modes, work) -> {
        try { work.run(); return CompletableFuture.completedFuture(null); }
        catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
    };
    /** Production installs the committed-menu coordinator before starting; isolated services can inspect standalone YAML. */
    public synchronized void policyGuard(PolicyGuard guard) {
        if (started || running.get()) throw new IllegalStateException("Maintenance already started");
        policyGuard = Objects.requireNonNull(guard);
    }
    public enum Mode { DRY_RUN, ARCHIVE }
    public record Item(String player, String key, String reason, UUID backupId) {}
    public record Report(Mode mode, int files, int candidates, int archived, int skipped, int failed,
                         boolean interrupted, int omitted, Map<String, Integer> reasons, List<Item> items) {}
    public record Restored(UUID player, String status, UUID safetyBackup) {}
    private final Path directory;
    private final LimitService limits;
    private final ProductionStore store;
    private final RetentionConfig config;
    private final Clock clock;
    private final Logger logger;
    private final RetentionArchive archives;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("DCMenu-retention").factory());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("DCMenu-retention-timer").factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closed;
    private boolean started;
    private CompletableFuture<Void> closing;
    public PlayerDataRetention(Path plugin, Path data, LimitService limits, ProductionStore store, RetentionConfig config, Logger logger) {
        this(plugin, data, limits, store, config, Clock.systemUTC(), logger, AtomicFiles::write);
    }
    PlayerDataRetention(Path plugin, Path data, LimitService limits, ProductionStore store, RetentionConfig config,
                        Clock clock, Logger logger, AtomicFiles.Replacement backupWriter) {
        directory = plugin.toAbsolutePath().normalize().resolve("playerdata"); this.limits = limits; this.store = store;
        this.config = config; this.clock = clock; this.logger = logger; archives = new RetentionArchive(data, backupWriter);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    /** Call only after production initialization. Daily means every 24 hours after readiness. */
    public synchronized void start() {
        if (started || closed) return; started = true;
        if (!config.enabled()) return;
        Runnable scheduled = () -> run(config.archive() ? Mode.ARCHIVE : Mode.DRY_RUN, null).whenComplete((report, failure) -> {
            if (failure != null) { logger.warning("玩家数据维护未运行：" + failureCode(failure)); return; }
            for (String line : describe(report)) logger.info(line);
        });
        if (config.onStartup()) timer.execute(scheduled);
        if (config.daily()) timer.scheduleAtFixedRate(scheduled, 24, 24, TimeUnit.HOURS);
    }
    public CompletableFuture<Report> run(Mode mode, UUID onlyPlayer) {
        return run(mode, onlyPlayer, dialogcanvas.audit.AuditLogger.Origin.system());
    }
    public CompletableFuture<Report> run(Mode mode, UUID onlyPlayer, dialogcanvas.audit.AuditLogger.Origin origin) {
        Objects.requireNonNull(mode);
        var operation = audit.begin(dialogcanvas.audit.AuditLogger.Event.RETENTION_SCAN, origin, onlyPlayer == null ? "*" : onlyPlayer.toString());
        var result = submit(() -> {
            try {
                var report = scan(mode, onlyPlayer, operation);
                operation.finish(report.failed() > 0 || report.interrupted() ? dialogcanvas.audit.AuditLogger.Result.PARTIAL : dialogcanvas.audit.AuditLogger.Result.SUCCESS,
                        report.interrupted() ? "SCAN_INTERRUPTED" : report.failed() > 0 ? "SCAN_PARTIAL" : mode == Mode.DRY_RUN ? "DRY_RUN_COMPLETE" : "ARCHIVE_SCAN_COMPLETE");
                return report;
            } catch (Exception failure) { operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED, failureCode(failure)); throw failure; }
        });
        result.whenComplete((ignored, failure) -> { if (failure != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED, failureCode(failure)); });
        return result.copy();
    }
    private Report scan(Mode mode, UUID onlyPlayer, dialogcanvas.audit.AuditLogger.Operation operation) throws Exception {
        var result = new Accumulator(mode, operation);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            result.add(new Item(onlyPlayer == null ? "*" : onlyPlayer.toString(), "", "NO_LIMIT_FILES", null)); return result.finish();
        }
        AtomicFiles.requireDirectory(directory);
        if (onlyPlayer != null) process(onlyPlayer, result);
        else try (var files = Files.newDirectoryStream(directory)) {
            int batch = 0;
            for (Path file : files) {
                if (closed) { result.interrupted = true; break; }
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.yml"))
                    result.add(new Item("<unknown-file>", "", "UNRECOGNIZED_FILE", null));
                else process(UUID.fromString(name.substring(0, 36)), result);
                if (++batch == config.batchSize()) { batch = 0; LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); }
            }
        }
        return result.finish();
    }
    private void process(UUID id, Accumulator result) {
        result.files++;
        var operation = result.mode == Mode.ARCHIVE ? result.operation.child(dialogcanvas.audit.AuditLogger.Event.RETENTION_ARCHIVE, id.toString()) : null;
        try {
            var checked = store.withPlayerMaintenance(id, () -> limits.maintain(id, file -> {
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Checked(null, null, "FILE_MISSING");
                byte[] before = read(file);
                var plan = LimitRetentionPlan.inspect(before, clock.instant(), config.expiredDays());
                var backup = new java.util.concurrent.atomic.AtomicReference<UUID>();
                if (plan.candidates() > 0) {
                    Map<?, ?> states = (Map<?, ?>) LimitRetentionPlan.parse(before).get("limits");
                    Map<String, String> expected = new HashMap<>();
                    for (var entry : plan.entries()) if (entry.candidate()) expected.put(entry.key(), (String) ((Map<?, ?>) states.get(entry.key())).get("resetMode"));
                    policyGuard.protect(Map.copyOf(expected), () -> {
                        result.candidates += plan.candidates();
                        if (result.mode == Mode.ARCHIVE) {
                            // Both player and menu guards remain held through backup and CAS.
                            backup.set(archives.create(id, before, plan.after(), clock.instant(), "cleanup").id());
                            store.recheckPlayerMaintenance(id);
                            if (!Arrays.equals(plan.after(), LimitRetentionPlan.inspect(before, clock.instant(), config.expiredDays()).after()))
                                throw new IOException("QUALIFICATION_CHANGED");
                            AtomicFiles.writeIfUnchanged(file, plan.after(), before);
                            if (operation != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.SUCCESS, "ARCHIVED");
                        }
                    }).join();
                }
                return new Checked(plan, backup.get(), null);
            })).join();
            if (checked.reason != null) { if (operation != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.SKIPPED, checked.reason); result.add(new Item(id.toString(), "", checked.reason, null)); return; }
            if (checked.backup != null) result.archived += checked.plan.candidates();
            for (var entry : checked.plan.entries()) result.add(new Item(id.toString(), entry.key(),
                    entry.candidate() ? checked.backup == null ? "CANDIDATE" : "ARCHIVED" : entry.reason(), checked.backup));
            if (operation != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.SKIPPED, "NO_ELIGIBLE_ENTRIES");
        } catch (Exception failure) {
            String code = failureCode(failure);
            if (operation != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED, code);
            if (!Set.of("TRANSACTION_UNRESOLVED", "RECOVERY_RECORD_PRESENT", "UNRECOGNIZED_TRANSACTION_RECORD", "MAINTENANCE_BUSY", "STORE_NOT_READY", "CURRENT_RULE_NOT_CONFIRMED").contains(code)) result.failed++;
            result.add(new Item(id.toString(), "", code, null));
        }
    }
    private record Checked(LimitRetentionPlan.Plan plan, UUID backup, String reason) {}
    public CompletableFuture<Restored> restore(UUID backupId) {
        return restore(backupId, dialogcanvas.audit.AuditLogger.Origin.system());
    }
    public CompletableFuture<Restored> restore(UUID backupId, dialogcanvas.audit.AuditLogger.Origin origin) {
        Objects.requireNonNull(backupId);
        var operation = audit.begin(dialogcanvas.audit.AuditLogger.Event.RETENTION_RESTORE, origin, backupId.toString());
        var result = submit(() -> {
          try {
            var backup = archives.read(backupId);
            return store.withPlayerMaintenance(backup.player(), () -> limits.maintain(backup.player(), file -> {
                byte[] current = read(file);
                if (Arrays.equals(current, backup.before())) { operation.finish(dialogcanvas.audit.AuditLogger.Result.SKIPPED, "ALREADY_ORIGINAL"); return new Restored(backup.player(), "ALREADY_ORIGINAL", null); }
                if (!Arrays.equals(current, backup.after())) throw new IOException("RESTORE_CONFLICT");
                // Even if an externally supplied archive contains pending data, never erase a reservation.
                var plan = LimitRetentionPlan.inspect(current, clock.instant(), config.expiredDays());
                if (plan.entries().stream().anyMatch(entry -> entry.reason().equals("PENDING_RESERVATION"))) throw new IOException("PENDING_RESERVATION");
                var safety = archives.create(backup.player(), current, backup.before(), clock.instant(), "restore");
                store.recheckPlayerMaintenance(backup.player());
                AtomicFiles.writeIfUnchanged(file, backup.before(), current);
                operation.finish(dialogcanvas.audit.AuditLogger.Result.SUCCESS, "RESTORED");
                return new Restored(backup.player(), "RESTORED", safety.id());
            })).join();
          } catch (Exception failure) { operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED, failureCode(failure)); throw failure; }
        });
        result.whenComplete((ignored, failure) -> { if (failure != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED, failureCode(failure)); });
        return result.copy();
    }
    private static byte[] read(Path file) throws IOException {
        AtomicFiles.requireTarget(file);
        if (Files.size(file) > LimitRetentionPlan.MAX_BYTES) throw new IOException("FILE_TOO_LARGE");
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(LimitRetentionPlan.MAX_BYTES + 1);
            if (bytes.length > LimitRetentionPlan.MAX_BYTES) throw new IOException("FILE_TOO_LARGE");
            return bytes;
        }
    }
    private synchronized <T> CompletableFuture<T> submit(Callable<T> task) {
        if (closed) return CompletableFuture.failedFuture(new RejectedExecutionException("RETENTION_STOPPED"));
        if (!running.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("MAINTENANCE_BUSY"));
        var actual = new CompletableFuture<T>();
        try { worker.execute(() -> {
            T value;
            try { value = task.call(); }
            catch (Throwable failure) { running.set(false); actual.completeExceptionally(failure); return; }
            running.set(false); actual.complete(value);
        }); } catch (RuntimeException failure) { running.set(false); actual.completeExceptionally(failure); }
        return actual.copy();
    }
    public static String failureCode(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof ProductionStore.MaintenanceDenied denied) return denied.code();
        String message = failure.getMessage();
        return message != null && Set.of("RESTORE_CONFLICT", "PENDING_RESERVATION", "FILE_TOO_LARGE", "MAINTENANCE_BUSY", "RETENTION_STOPPED", "BACKUP_DIGEST_MISMATCH", "QUALIFICATION_CHANGED").contains(message)
                ? message : "UNSAFE_OR_IO_FAILURE"; // No parser snippets, player data, raw paths, or provider exception messages.
    }
    public static String reason(String code) {
        return switch (code) {
            case "CANDIDATE" -> "可归档：计数和冷却均已失效且过了保留期";
            case "ARCHIVED" -> "已归档失效条目（原内容已备份）";
            case "PERMANENT" -> "永久限购，必须保留";
            case "ACTIVE_COUNT" -> "计数周期尚未结束";
            case "ACTIVE_COOLDOWN" -> "冷却尚未结束";
            case "RETENTION_GRACE" -> "已到期但尚在额外保留期内";
            case "PENDING_RESERVATION" -> "存在未决预留，保留整个文件";
            case "TRANSACTION_UNRESOLVED" -> "交易正在执行或结果待核查";
            case "RECOVERY_RECORD_PRESENT" -> "存在补偿恢复记录，请人工核查";
            case "UNRECOGNIZED_TRANSACTION_RECORD" -> "交易或恢复目录存在无法识别的记录";
            case "UNKNOWN_RESET_MODE", "MISSING_OR_INVALID_TIME", "UNRELIABLE_TIME" -> "缺少可靠重置类型或时间，不能证明已失效";
            case "FILE_TOO_LARGE" -> "超过首版维护大小上限，保留文件";
            case "RESTORE_CONFLICT" -> "文件已有其他修改，拒绝覆盖恢复";
            case "QUALIFICATION_CHANGED" -> "备份期间清理资格或系统时间变化，本次保留原状态";
            case "CURRENT_RULE_NOT_CONFIRMED" -> "当前菜单规则无法匹配、重置类型已变或共享规则不一致，保守保留";
            case "EMPTY_LIMITS", "NO_LIMIT_FILES", "FILE_MISSING" -> "没有可清理的限购条目";
            case "MAINTENANCE_BUSY" -> "已有维护正在处理，请稍后重试";
            case "STORE_NOT_READY", "RETENTION_STOPPED" -> "数据服务未就绪或已停止";
            default -> "结构未知、路径不安全、备份或文件操作失败，未继续清理";
        };
    }
    public static List<String> describe(Report report) {
        var lines = new ArrayList<String>();
        lines.add((report.mode == Mode.DRY_RUN ? "只检查，不修改" : "安全归档") + "：文件 " + report.files + "，候选条目 " + report.candidates
                + "，已归档 " + report.archived + "，跳过 " + report.skipped + "，失败文件 " + report.failed + (report.interrupted ? "；停止后未处理剩余文件" : ""));
        lines.add("玩家业务 JSON、交易意图、收据和恢复记录全部保留；未删除任何完整玩家文件。");
        report.reasons.forEach((key, count) -> lines.add(reason(key) + "：" + count + "（" + key + "）"));
        for (var item : report.items) lines.add(item.player + (item.key.isEmpty() ? "" : " / " + item.key) + "：" + reason(item.reason)
                + (item.backupId == null ? "" : "；备份 " + item.backupId));
        if (report.omitted > 0) lines.add("另有 " + report.omitted + " 项未展开；请指定玩家 UUID 复查。统计包含全部条目。");
        return List.copyOf(lines);
    }
    private static final class Accumulator {
        final Mode mode; final dialogcanvas.audit.AuditLogger.Operation operation; int files, candidates, archived, skipped, failed, omitted; boolean interrupted;
        final Map<String, Integer> reasons = new TreeMap<>(); final List<Item> items = new ArrayList<>();
        Accumulator(Mode mode, dialogcanvas.audit.AuditLogger.Operation operation) { this.mode = mode; this.operation = operation; }
        void add(Item item) {
            reasons.merge(item.reason, 1, Integer::sum);
            if (!item.reason.equals("CANDIDATE") && !item.reason.equals("ARCHIVED")) skipped++;
            if (items.size() < 200) items.add(item); else omitted++;
        }
        Report finish() { return new Report(mode, files, candidates, archived, skipped, failed, interrupted, omitted, Map.copyOf(reasons), List.copyOf(items)); }
    }
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing.copy();
        closed = true; timer.shutdownNow(); worker.shutdown(); closing = new CompletableFuture<>();
        Thread.ofVirtual().name("DCMenu-retention-shutdown").start(() -> {
            try { while (!worker.awaitTermination(1, TimeUnit.SECONDS) || !timer.awaitTermination(1, TimeUnit.SECONDS)) { /* drain accepted maintenance */ } closing.complete(null); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); closing.completeExceptionally(failure); }
        });
        return closing.copy();
    }
    @Override public void close() { closeAsync(); }
}
