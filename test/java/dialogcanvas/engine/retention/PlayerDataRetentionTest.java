package dialogcanvas.engine.retention;

import dialogcanvas.engine.limit.LimitService;
import dialogcanvas.engine.production.ProductionStore;
import dialogcanvas.model.MenuJson;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.AtomicFiles;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static dialogcanvas.engine.retention.PlayerDataRetention.Mode.*;

class PlayerDataRetentionTest {
    @Test void auditRecordsDryRunArchiveAndRestoreActualOutcomesWithoutPlayerValues() throws Exception {
        fixture(Map.of("expired", expired("TIMED")));
        var audit = new dialogcanvas.audit.AuditLogger(root, Logger.getAnonymousLogger()); retention.auditLogger(audit);
        try {
            run(DRY_RUN); var archived = run(ARCHIVE); retention.restore(backup(archived)).get(10, TimeUnit.SECONDS);
            retention.closeAsync().join(); audit.closeAsync().get(5, TimeUnit.SECONDS);
            var rows = Files.readAllLines(root.resolve("logs/audit.log")).stream().map(MenuJson.createMapper()::readTree).toList();
            assertEquals(4, rows.size());
            assertEquals(List.of("RETENTION_SCAN", "RETENTION_ARCHIVE", "RETENTION_SCAN", "RETENTION_RESTORE"), rows.stream().map(row -> row.path("eventType").asString()).toList());
            assertTrue(rows.stream().allMatch(row -> row.path("result").asString().equals("SUCCESS")));
            assertEquals(rows.get(1).path("operationId"), rows.get(2).path("operationId"));
            assertFalse(Files.readString(root.resolve("logs/audit.log")).contains("lastCommittedAt"));
        } finally { audit.closeAsync().join(); }
    }
    @TempDir Path root;
    final UUID id = UUID.randomUUID();
    final Instant now = Instant.parse("2030-03-01T00:00:00Z");
    ServerMock server; Scheduler scheduler; LimitService limits; ProductionStore store; PlayerDataRetention retention;
    AtomicReference<Instant> instant = new AtomicReference<>(now);
    Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant.get(); }
    };
    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); scheduler = new Scheduler(MockBukkit.createMockPlugin());
        limits = new LimitService(root, scheduler, clock, ZoneId.of("Asia/Shanghai"));
        store = new ProductionStore(root.resolve("data")); store.initialize().get(5, TimeUnit.SECONDS);
        retention = service(AtomicFiles::write);
    }
    @AfterEach void close() throws Exception {
        retention.closeAsync().get(10, TimeUnit.SECONDS);
        await(limits.closeAsync()); store.closeAsync().get(5, TimeUnit.SECONDS);
        scheduler.close(); MockBukkit.unmock();
    }
    PlayerDataRetention service(AtomicFiles.Replacement writer) {
        return new PlayerDataRetention(root, root.resolve("data"), limits, store,
                new RetentionConfig(false, true, true, false, 0, 2), clock, Logger.getAnonymousLogger(), writer);
    }
    Path file() { return root.resolve("playerdata/" + id + ".yml"); }
    Map<String, Object> expired(String mode) {
        var state = new LinkedHashMap<String, Object>();
        state.put("count", 2); state.put("resetMode", mode);
        state.put("lastCommittedAt", now.minusSeconds(120).toString());
        state.put("resetAt", now.minusSeconds(60).toString()); state.put("cooldownUntil", now.minusSeconds(120).toString());
        return state;
    }
    byte[] fixture(Map<String, Object> entries) throws Exception {
        Files.createDirectories(file().getParent());
        var value = new LinkedHashMap<String, Object>(); value.put("owner-note", "保留其他根字段"); value.put("limits", entries);
        byte[] bytes = new Yaml().dump(value).getBytes(StandardCharsets.UTF_8); Files.write(file(), bytes); return bytes;
    }
    Map<String, Object> entries() throws Exception { return LimitRetentionPlan.parse(Files.readAllBytes(file())); }
    PlayerDataRetention.Report run(PlayerDataRetention.Mode mode) throws Exception { return retention.run(mode, id).get(10, TimeUnit.SECONDS); }
    boolean reason(PlayerDataRetention.Report report, String code) { return report.reasons().containsKey(code); }
    UUID backup(PlayerDataRetention.Report report) { return report.items().stream().map(PlayerDataRetention.Item::backupId).filter(Objects::nonNull).findFirst().orElseThrow(); }
    <T> T await(CompletableFuture<T> future) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && System.nanoTime() < until) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        return future.get(1, TimeUnit.SECONDS);
    }
    @ParameterizedTest @ValueSource(strings = {"DAILY", "WEEKLY", "MONTHLY", "TIMED"})
    void dryRunThenArchivesOnlyExpiredEntriesAndPreservesBusinessData(String mode) throws Exception {
        byte[] before = fixture(Map.of("expired", expired(mode), "permanent", expired("NEVER")));
        Path business = root.resolve("data/player-data/" + id + ".json"); Files.writeString(business, "{\"quest_count\":100,\"nested\":{\"secret\":\"fixture\"}}");
        byte[] businessBefore = Files.readAllBytes(business);
        Files.setLastModifiedTime(file(), FileTime.from(now.plusSeconds(99999)));
        var dry = run(DRY_RUN); assertEquals(1, dry.candidates()); assertEquals(0, dry.archived()); assertTrue(reason(dry, "PERMANENT"));
        assertArrayEquals(before, Files.readAllBytes(file())); assertFalse(Files.exists(root.resolve("data/retention-backups")));
        var archived = run(ARCHIVE); assertEquals(1, archived.archived());
        var remaining = (Map<?, ?>) entries().get("limits"); assertEquals(Set.of("permanent"), remaining.keySet());
        assertEquals("保留其他根字段", entries().get("owner-note")); assertArrayEquals(businessBefore, Files.readAllBytes(business));
        var saved = new RetentionArchive(root.resolve("data"), AtomicFiles::write).read(backup(archived)); assertArrayEquals(before, saved.before());
        assertTrue(Files.isRegularFile(file()), "Never delete the entire player file");
    }
    @ParameterizedTest @ValueSource(strings = {"NEVER", "legacy", "future-count", "future-cooldown", "pending-quantity", "pending-since", "unknown-field", "missing-time", "invalid-count", "future-commit"})
    void mustRetainProtectedOrUnrecognizedStateRegardlessOfMtime(String kind) throws Exception {
        var state = expired("TIMED");
        switch (kind) {
            case "NEVER" -> { state.put("resetMode", "NEVER"); state.remove("resetAt"); }
            case "legacy" -> { state.remove("resetMode"); state.remove("lastCommittedAt"); }
            case "future-count" -> state.put("resetAt", now.plusSeconds(1).toString());
            case "future-cooldown" -> state.put("cooldownUntil", now.plusSeconds(1).toString());
            case "pending-quantity" -> state.put("pendingQuantity", 1);
            case "pending-since" -> state.put("pendingSince", now.minusSeconds(1000000).toString());
            case "unknown-field" -> state.put("futureVersionState", 9);
            case "missing-time" -> state.remove("cooldownUntil");
            case "invalid-count" -> state.put("count", -1);
            case "future-commit" -> state.put("lastCommittedAt", now.plusSeconds(10).toString());
        }
        byte[] before = fixture(Map.of("protected", state)); Files.setLastModifiedTime(file(), FileTime.from(Instant.EPOCH));
        var report = run(ARCHIVE); assertEquals(0, report.candidates()); assertEquals(1, report.skipped());
        assertArrayEquals(before, Files.readAllBytes(file())); assertFalse(Files.exists(root.resolve("data/retention-backups")));
    }
    @Test void graceUsesExpirationNotLastCommitOrMtimeAndBoundaryIsInclusive() throws Exception {
        retention.closeAsync().join(); retention = new PlayerDataRetention(root, root.resolve("data"), limits, store,
                new RetentionConfig(false, true, true, false, 30, 2), clock, Logger.getAnonymousLogger(), AtomicFiles::write);
        var state = expired("TIMED"); state.put("lastCommittedAt", now.minus(Duration.ofDays(100)).toString());
        state.put("resetAt", now.minus(Duration.ofDays(30)).plusNanos(1).toString());
        state.put("cooldownUntil", now.minus(Duration.ofDays(90)).toString());
        fixture(Map.of("timed", state)); assertTrue(reason(run(DRY_RUN), "RETENTION_GRACE"));
        instant.set(now.plusNanos(1)); assertEquals(1, run(DRY_RUN).candidates());
    }
    @ParameterizedTest @ValueSource(strings = {"pending", "effectsPending", "recoveryRequired", "unknown"})
    void unresolvedPersistentTransactionsBlockCleanup(String status) throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED")));
        UUID transaction = store.begin(id, MenuJson.createMapper().createObjectNode(), "test", Map.of()).join();
        if (!status.equals("pending")) store.finish(id, transaction, status).join();
        assertTrue(reason(run(ARCHIVE), "TRANSACTION_UNRESOLVED")); assertArrayEquals(before, Files.readAllBytes(file()));
        retention.closeAsync().join(); store.closeAsync().join();
        store = new ProductionStore(root.resolve("data")); store.initialize().join(); retention = service(AtomicFiles::write);
        assertTrue(reason(run(ARCHIVE), "TRANSACTION_UNRESOLVED")); assertArrayEquals(before, Files.readAllBytes(file()));
        assertTrue(Files.exists(root.resolve("data/transactions/" + transaction + ".json")));
    }
    @Test void recoveryRecordsAndUnattributableMalformedRecordsAreNeverRemoved() throws Exception {
        byte[] before = fixture(Map.of("old", expired("DAILY"))); UUID tx = UUID.randomUUID();
        Path recovery = root.resolve("data/recovery/" + tx + ".json");
        var record = MenuJson.createMapper().createObjectNode().put("transactionId", tx.toString()).put("playerId", id.toString());
        Files.write(recovery, MenuJson.createMapper().writeValueAsBytes(record));
        assertTrue(reason(run(ARCHIVE), "RECOVERY_RECORD_PRESENT"));
        Files.writeString(root.resolve("data/transactions/unrecognized.json"), "{broken");
        assertTrue(reason(run(ARCHIVE), "UNRECOGNIZED_TRANSACTION_RECORD"));
        assertArrayEquals(before, Files.readAllBytes(file())); assertTrue(Files.exists(recovery));
    }
    @Test void terminalReceiptAndBusinessCacheRemainUntouched() throws Exception {
        fixture(Map.of("old", expired("TIMED")));
        UUID tx = store.begin(id, MenuJson.createMapper().createObjectNode(), "test", Map.of()).join();
        store.set(id, "quest_count", MenuJson.createMapper().getNodeFactory().numberNode(9)).join();
        store.finish(id, tx, "committed").join(); Path receipt = root.resolve("data/transactions/" + tx + ".json"); byte[] receiptBefore = Files.readAllBytes(receipt);
        assertEquals(1, run(ARCHIVE).archived()); assertEquals(9, store.find(id, "quest_count").orElseThrow().asInt());
        assertArrayEquals(receiptBefore, Files.readAllBytes(receipt));
    }
    @Test void backupFailureLeavesExactOriginalAndDoesNotConsumeDryRun() throws Exception {
        retention.closeAsync().join(); var backupCalls = new AtomicInteger();
        retention = service((path, bytes) -> { assertFalse(org.bukkit.Bukkit.isPrimaryThread()); backupCalls.incrementAndGet(); throw new IOException("fixture backup failure"); });
        byte[] before = fixture(Map.of("old", expired("TIMED")));
        assertEquals(1, run(DRY_RUN).candidates()); assertEquals(0, backupCalls.get());
        var result = run(ARCHIVE); assertEquals(1, result.candidates()); assertEquals(1, result.failed()); assertEquals(0, result.archived());
        assertArrayEquals(before, Files.readAllBytes(file()));
    }
    @Test void clockRollbackDuringBackupRechecksEligibilityAndKeepsOriginal() throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED"))); retention.closeAsync().join();
        retention = service((path, bytes) -> { AtomicFiles.write(path, bytes); instant.set(now.minusSeconds(200)); });
        var report = run(ARCHIVE); assertTrue(reason(report, "QUALIFICATION_CHANGED")); assertEquals(0, report.archived());
        assertArrayEquals(before, Files.readAllBytes(file()));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stateIsRecheckedAfterBackupBeforeChangingLimitFile(boolean recoveryFile) throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED"))); retention.closeAsync().join();
        retention = service((path, bytes) -> {
            AtomicFiles.write(path, bytes);
            if (!recoveryFile) store.markUncertain(id);
            else {
                UUID tx = UUID.randomUUID();
                Files.writeString(root.resolve("data/recovery/" + tx + ".json"), "{\"transactionId\":\"" + tx + "\",\"playerId\":\"" + id + "\"}");
            }
        });
        var report = run(ARCHIVE); assertEquals(0, report.archived());
        assertTrue(reason(report, recoveryFile ? "RECOVERY_RECORD_PRESENT" : "TRANSACTION_UNRESOLVED"));
        assertArrayEquals(before, Files.readAllBytes(file()));
        try (var files = Files.list(root.resolve("data/retention-backups"))) { assertEquals(1, files.count()); }
    }
    @Test void newTransactionsAreRejectedWhileArchiveGateIsHeldThenCanProceed() throws Exception {
        retention.closeAsync().join(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        retention = service((path, bytes) -> {
            assertFalse(org.bukkit.Bukkit.isPrimaryThread()); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test gate timeout"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            AtomicFiles.write(path, bytes);
        });
        fixture(Map.of("old", expired("TIMED"))); var cleanup = retention.run(ARCHIVE, id);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(CompletionException.class, () -> store.begin(id, MenuJson.createMapper().createObjectNode(), "test", Map.of()).join());
            release.countDown(); assertEquals(1, cleanup.get(5, TimeUnit.SECONDS).archived());
            UUID tx = store.begin(id, MenuJson.createMapper().createObjectNode(), "test", Map.of()).join(); store.finish(id, tx, "denied").join();
        } finally { release.countDown(); }
    }
    @Test void uuidQueueWaitsForActualLimitExecutionAndCallerCancellationDoesNotReleaseIt() throws Exception {
        var gate = new CompletableFuture<LimitService.Outcome>(); var entered = new AtomicBoolean();
        var purchase = limits.execute(id, new LimitService.Policy("same", 5, 1, LimitService.Reset.TIMED, 60, 20), () -> true, () -> true,
                () -> { entered.set(true); return gate; });
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!entered.get() && System.nanoTime() < end) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        assertTrue(entered.get());
        var cleanup = retention.run(ARCHIVE, id); Thread.sleep(30); assertFalse(cleanup.isDone());
        purchase.cancel(false); gate.complete(LimitService.Outcome.COMMITTED);
        var result = cleanup.get(5, TimeUnit.SECONDS); assertEquals(0, result.archived()); assertTrue(reason(result, "ACTIVE_COUNT"));
        assertEquals(1, ((Map<?, ?>) ((Map<?, ?>) entries().get("limits")).get("same")).get("count"));
    }
    @Test void timedExactBoundaryRestartAndRestorationKeepCountSemantics() throws Exception {
        var policy = new LimitService.Policy("timed", 5, 1, LimitService.Reset.TIMED, 60, 30);
        assertEquals(LimitService.Status.EXECUTED, await(limits.execute(id, policy, () -> true, () -> true, () -> CompletableFuture.completedFuture(LimitService.Outcome.COMMITTED))).status());
        assertTrue(Files.readString(file()).contains("resetMode: TIMED"));
        instant.set(now.plusSeconds(60).minusNanos(1)); assertEquals(0, run(DRY_RUN).candidates());
        instant.set(now.plusSeconds(60)); var archived = run(ARCHIVE); UUID archiveId = backup(archived);
        retention.closeAsync().join(); await(limits.closeAsync()); store.closeAsync().join();
        limits = new LimitService(root, scheduler, clock, ZoneId.of("Asia/Shanghai")); store = new ProductionStore(root.resolve("data")); store.initialize().join(); retention = service(AtomicFiles::write);
        assertEquals("RESTORED", retention.restore(archiveId).get(5, TimeUnit.SECONDS).status());
        assertEquals("ALREADY_ORIGINAL", retention.restore(archiveId).get(5, TimeUnit.SECONDS).status());
        assertEquals(LimitService.Status.EXECUTED, await(limits.execute(id, policy, () -> true, () -> true, () -> CompletableFuture.completedFuture(LimitService.Outcome.COMMITTED))).status());
        assertEquals(1, ((Map<?, ?>) ((Map<?, ?>) entries().get("limits")).get("timed")).get("count"));
    }
    @Test void restoreRefusesNewPurchasesAndCorruptOrRedirectedBackups() throws Exception {
        fixture(Map.of("old", expired("TIMED"))); UUID archiveId = backup(run(ARCHIVE));
        await(limits.execute(id, new LimitService.Policy("new", 9, 1, LimitService.Reset.NEVER, 0, 0), () -> true, () -> true, () -> CompletableFuture.completedFuture(LimitService.Outcome.COMMITTED)));
        byte[] changed = Files.readAllBytes(file());
        assertEquals("RESTORE_CONFLICT", PlayerDataRetention.failureCode(assertThrows(ExecutionException.class, () -> retention.restore(archiveId).get(5, TimeUnit.SECONDS))));
        assertArrayEquals(changed, Files.readAllBytes(file()));
        Path backupFile = root.resolve("data/retention-backups/" + archiveId + "/record.json");
        String record = Files.readString(backupFile).replace("\"beforeSha256\":\"", "\"beforeSha256\":\"00"); Files.writeString(backupFile, record);
        assertEquals("BACKUP_DIGEST_MISMATCH", PlayerDataRetention.failureCode(assertThrows(ExecutionException.class, () -> retention.restore(archiveId).get(5, TimeUnit.SECONDS))));
        assertArrayEquals(changed, Files.readAllBytes(file()));
    }
    @Test void interruptionAfterBackupBeforeReplacementAndSourceRaceLeaveRecoverableEvidence() throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED")));
        retention.closeAsync().join(); retention = service((path, bytes) -> { AtomicFiles.write(path, bytes); throw new IOException("simulate interruption after complete backup"); });
        assertEquals(1, run(ARCHIVE).failed()); assertArrayEquals(before, Files.readAllBytes(file()));
        UUID archiveId;
        try (var files = Files.list(root.resolve("data/retention-backups"))) { archiveId = UUID.fromString(files.findFirst().orElseThrow().getFileName().toString()); }
        retention.closeAsync().join(); retention = service(AtomicFiles::write);
        assertEquals("ALREADY_ORIGINAL", retention.restore(archiveId).get(5, TimeUnit.SECONDS).status());
        retention.closeAsync().join(); retention = service((path, bytes) -> { AtomicFiles.write(path, bytes); Files.writeString(file(), "limits: {}\nowner-note: manual\n"); });
        assertEquals(1, run(ARCHIVE).failed()); assertTrue(Files.readString(file()).contains("manual"));
    }
    @Test void redirectedLimitDirectoryAndBackupDirectoryCannotWriteOutsideTheirRoots() throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED")));
        Path outside = root.resolve("outside"); Files.createDirectory(outside);
        dialogcanvas.storage.PersistenceTestLinks.redirectDirectory(root.resolve("data/retention-backups"), outside);
        assertEquals(1, run(ARCHIVE).failed()); assertArrayEquals(before, Files.readAllBytes(file()));
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }
    @Test void disabledAutomationAndStoppedServiceCannotDeleteData() throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED"))); retention.start();
        assertEquals(1, run(DRY_RUN).candidates()); assertArrayEquals(before, Files.readAllBytes(file()));
        retention.closeAsync().get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> retention.run(ARCHIVE, id).get()); assertArrayEquals(before, Files.readAllBytes(file()));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void readyStartupTaskUsesExplicitConfiguredModeAndRunsOffMain(boolean archive) throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED"))); retention.closeAsync().join();
        var done = new CountDownLatch(1); var log = Logger.getAnonymousLogger(); log.setUseParentHandlers(false);
        var offMain = new AtomicBoolean();
        log.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { offMain.set(!org.bukkit.Bukkit.isPrimaryThread()); done.countDown(); }
            public void flush() {} public void close() {}
        });
        retention = new PlayerDataRetention(root, root.resolve("data"), limits, store,
                new RetentionConfig(true, true, true, archive, 0, 2), clock, log, AtomicFiles::write);
        retention.start(); assertTrue(done.await(5, TimeUnit.SECONDS)); retention.closeAsync().get(5, TimeUnit.SECONDS);
        assertTrue(offMain.get()); assertEquals(archive, Files.exists(root.resolve("data/retention-backups")));
        if (!archive) assertArrayEquals(before, Files.readAllBytes(file()));
        else assertTrue(((Map<?, ?>) entries().get("limits")).isEmpty());
    }
    @Test void restoreAlsoRequiresFreshBackupAndNeverOverwritesTheOriginalBackup() throws Exception {
        byte[] before = fixture(Map.of("old", expired("TIMED"))); UUID archiveId = backup(run(ARCHIVE));
        Path first = root.resolve("data/retention-backups/" + archiveId + "/record.json"); byte[] originalBackup = Files.readAllBytes(first);
        byte[] after = Files.readAllBytes(file()); retention.closeAsync().join();
        retention = service((path, bytes) -> { throw new IOException("restore safety backup failure"); });
        assertThrows(ExecutionException.class, () -> retention.restore(archiveId).get(5, TimeUnit.SECONDS)); assertArrayEquals(after, Files.readAllBytes(file()));
        retention.closeAsync().join(); retention = service(AtomicFiles::write);
        var restored = retention.restore(archiveId).get(5, TimeUnit.SECONDS); assertEquals("RESTORED", restored.status()); assertNotEquals(archiveId, restored.safetyBackup());
        assertArrayEquals(before, Files.readAllBytes(file())); assertArrayEquals(originalBackup, Files.readAllBytes(first));
    }
    @Test void malformedYamlAliasesAndInvalidUtf8AreNotRewritten() throws Exception {
        for (byte[] content : List.of("limits: [broken".getBytes(StandardCharsets.UTF_8), "limits: &a {x: *a}".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xFF})) {
            Files.createDirectories(file().getParent()); Files.write(file(), content);
            assertEquals(1, run(ARCHIVE).failed()); assertArrayEquals(content, Files.readAllBytes(file()));
        }
    }
    @Test void batchingKeepsUnknownFileAndReportsEveryPlayer() throws Exception {
        fixture(Map.of("old", expired("TIMED")));
        for (int i = 0; i < 5; i++) Files.copy(file(), file().getParent().resolve(UUID.randomUUID() + ".yml"));
        Files.writeString(file().getParent().resolve("owner-notes.yml"), "do not remove");
        var report = retention.run(DRY_RUN, null).get(5, TimeUnit.SECONDS);
        assertEquals(6, report.files()); assertEquals(6, report.candidates()); assertTrue(reason(report, "UNRECOGNIZED_FILE"));
        assertEquals("do not remove", Files.readString(file().getParent().resolve("owner-notes.yml")));
    }
}
