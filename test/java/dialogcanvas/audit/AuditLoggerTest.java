package dialogcanvas.audit;

import dialogcanvas.model.MenuJson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {
    @TempDir Path root;
    final List<String> alarms = new CopyOnWriteArrayList<>();
    Logger logger() {
        var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { alarms.add(record.getMessage()); }
            public void flush() {} public void close() {}
        }); return logger;
    }
    AuditLogger service(int capacity, long size, AuditLogger.FileOps ops) { return new AuditLogger(root, logger(), Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC), capacity, size, ops); }
    void emit(AuditLogger audit, String target) { audit.begin(AuditLogger.Event.MENU_UPDATE, AuditLogger.Origin.web(), target).finish(AuditLogger.Result.SUCCESS, "OK"); }
    // This checks complete drainage, not disk throughput. The 800-record case
    // performs one force(false) per append and can exceed 10 seconds on Windows.
    void stop(AuditLogger audit) throws Exception { audit.closeAsync().get(30, TimeUnit.SECONDS); }
    List<tools.jackson.databind.JsonNode> entries() throws Exception {
        var result = new ArrayList<tools.jackson.databind.JsonNode>();
        if (Files.exists(root.resolve("logs/audit.log"))) for (String line : Files.readAllLines(root.resolve("logs/audit.log"), StandardCharsets.UTF_8)) result.add(MenuJson.createMapper().readTree(line));
        return result;
    }
    @Test void concurrentAppendProducesExactlyOneUtf8JsonObjectPerOperationAndDrains() throws Exception {
        var audit = service(2048, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {});
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int n = 0; n < 800; n++) { final int id = n; tasks.add(pool.submit(() -> emit(audit, "menu_" + id))); }
            for (var task : tasks) task.get();
        }
        stop(audit); var rows = entries(); assertEquals(800, rows.size());
        assertEquals(800, rows.stream().map(row -> row.path("operationId").asString()).distinct().count());
        for (var row : rows) {
            assertTrue(row.isObject()); assertEquals("local-admin-session", row.path("actor").asString()); assertEquals("web", row.path("source").asString());
            assertEquals("2030-01-01T00:00:00Z", row.path("time").asString()); assertEquals("SUCCESS", row.path("result").asString());
            assertEquals(Set.of("time", "eventType", "operationId", "source", "actor", "target", "result", "code", "beforeRevision", "afterRevision"), row.propertyNames());
        }
        assertEquals(800, audit.stats().written()); assertEquals(0, audit.stats().failed()); assertEquals(0, audit.stats().rejected()); assertTrue(audit.stats().healthy());
    }
    @Test void fixedSchemaRejectsInjectedLinesAndDoesNotAcceptSecretActorsRevisionsOrDiagnostics() throws Exception {
        var audit = service(10, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {});
        String secret = "private-token\n{\"password\":\"secret\"}";
        var operation = audit.begin(AuditLogger.Event.PASSWORD_CHANGE, AuditLogger.Origin.web(), secret);
        operation.finish(AuditLogger.Result.FAILED, secret, secret, "$2b$12$private-hash"); operation.finish(AuditLogger.Result.SUCCESS, "DUPLICATE");
        assertThrows(IllegalArgumentException.class, () -> new AuditLogger.Origin("web", secret));
        stop(audit); var rows = entries(); assertEquals(1, rows.size()); assertEquals("<invalid-target>", rows.getFirst().path("target").asString());
        assertTrue(rows.getFirst().path("beforeRevision").isNull()); assertEquals("UNCLASSIFIED", rows.getFirst().path("code").asString());
        assertFalse(Files.readString(root.resolve("logs/audit.log")).contains("private"));
    }
    @Test void rotationsHaveUniqueNamesPreserveAllEventsAndNeverOverwriteHistory() throws Exception {
        var audit = service(100, 1200, new AuditLogger.FileOps() {});
        for (int n = 0; n < 40; n++) emit(audit, "menu_" + n); stop(audit);
        List<Path> histories;
        try (var files = Files.list(root.resolve("logs"))) { histories = files.filter(p -> p.getFileName().toString().startsWith("audit-")).toList(); }
        assertTrue(histories.size() > 2); var original = Files.readAllBytes(histories.getFirst());
        long lines = entries().size(); for (var file : histories) { assertTrue(Files.size(file) <= 1200); for (var line : Files.readAllLines(file)) { assertTrue(MenuJson.createMapper().readTree(line).isObject()); lines++; } }
        assertEquals(40, lines);
        var restarted = service(10, 1200, new AuditLogger.FileOps() {}); emit(restarted, "after_restart"); stop(restarted);
        assertArrayEquals(original, Files.readAllBytes(histories.getFirst()));
    }
    @Test void productionTenMebibyteBoundaryRotatesBeforeOverflow() throws Exception {
        assertEquals(10L * 1024 * 1024, AuditLogger.MAX_BYTES); Files.createDirectories(root.resolve("logs"));
        String line = "{\"fixture\":\"" + "x".repeat(10000) + "\"}\n";
        String content = line.repeat((int) (AuditLogger.MAX_BYTES / line.length()));
        Files.writeString(root.resolve("logs/audit.log"), content);
        var audit = service(20, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {});
        for (int n = 0; n < 20; n++) emit(audit, "menu"); stop(audit);
        try (var files = Files.list(root.resolve("logs"))) {
            var history = files.filter(p -> p.getFileName().toString().startsWith("audit-")).toList(); assertEquals(1, history.size()); assertTrue(Files.size(history.getFirst()) <= AuditLogger.MAX_BYTES);
        }
        assertTrue(Files.size(root.resolve("logs/audit.log")) <= AuditLogger.MAX_BYTES); assertEquals(20, audit.stats().written());
    }
    @Test void boundedQueueRejectsWithoutBlockingBusinessAndReportsEveryMissingEvent() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var audit = service(1, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {
            public void append(Path file, byte[] bytes) throws IOException {
                entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException(); } catch (InterruptedException failure) { throw new IOException(failure); }
                AuditLogger.FileOps.super.append(file, bytes);
            }
        });
        try {
            emit(audit, "one"); assertTrue(entered.await(5, TimeUnit.SECONDS)); emit(audit, "two"); emit(audit, "three");
            assertEquals(1, audit.stats().rejected()); assertTrue(alarms.stream().anyMatch(s -> s.contains("AUDIT_QUEUE_FULL")));
            var closing = audit.closeAsync(); assertFalse(closing.isDone()); emit(audit, "after_close"); assertEquals(2, audit.stats().rejected());
            release.countDown(); closing.get(5, TimeUnit.SECONDS); assertEquals(2, entries().size()); assertFalse(audit.stats().healthy());
        } finally { release.countDown(); stop(audit); }
    }
    @Test void appendFailurePoisonsWriterDoesNotRetryAndDoesNotLeakException() throws Exception {
        var calls = new AtomicInteger(); var audit = service(10, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {
            public void append(Path file, byte[] bytes) throws IOException { calls.incrementAndGet(); throw new IOException("private-cookie-secret"); }
        });
        emit(audit, "one"); emit(audit, "two"); stop(audit);
        assertEquals(1, calls.get()); assertEquals(2, audit.stats().failed()); assertEquals(0, audit.stats().written());
        assertEquals(2, alarms.stream().filter(s -> s.contains("operationId=")).count()); assertFalse(String.join("", alarms).contains("private-cookie-secret"));
    }
    @Test void rotationFailurePreservesHistoryAndStopsAppendingWithoutOrdinaryMoveFallback() throws Exception {
        var calls = new AtomicInteger(); var audit = service(10, 300, new AuditLogger.FileOps() {
            public void rotate(Path source, Path destination) throws IOException { calls.incrementAndGet(); throw new AtomicMoveNotSupportedException("secret", "secret", "secret"); }
        });
        for (int n = 0; n < 4; n++) emit(audit, "menu"); stop(audit);
        assertEquals(1, calls.get()); assertEquals(1, entries().size()); assertEquals(3, audit.stats().failed()); assertFalse(String.join("", alarms).contains("secret"));
    }
    @Test void partialAppendIsPreservedAndRestartRotatesInvalidTailBeforeNewValidLine() throws Exception {
        var audit = service(10, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {
            public void append(Path file, byte[] bytes) throws IOException { Files.writeString(file, "{\"time\":"); throw new IOException(); }
        }); emit(audit, "one"); stop(audit); byte[] partial = Files.readAllBytes(root.resolve("logs/audit.log"));
        var restart = service(10, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {}); emit(restart, "two"); stop(restart);
        assertEquals(1, entries().size());
        try (var files = Files.list(root.resolve("logs"))) { var history = files.filter(p -> p.getFileName().toString().startsWith("audit-")).findFirst().orElseThrow(); assertArrayEquals(partial, Files.readAllBytes(history)); }
        assertTrue(alarms.stream().anyMatch(s -> s.contains("AUDIT_INVALID_EXISTING_ROTATED")));
    }
    @Test void unsafeDirectoryFailsLoudlyWithoutFollowingFileAsDirectory() throws Exception {
        Files.writeString(root.resolve("logs"), "preserve"); var audit = service(10, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {});
        emit(audit, "one"); stop(audit); assertEquals("preserve", Files.readString(root.resolve("logs"))); assertEquals(1, audit.stats().failed()); assertFalse(audit.stats().healthy());
        assertTrue(alarms.stream().anyMatch(s -> s.contains("AUDIT_INITIALIZATION_FAILED")));
    }
    @Test void diagnosticHandlerFailureCannotEscapeIntoCommittedBusinessResult() throws Exception {
        Files.writeString(root.resolve("logs"), "blocked-directory");
        Logger broken = Logger.getAnonymousLogger(); broken.setUseParentHandlers(false);
        broken.addHandler(new Handler() {
            public void publish(LogRecord record) { throw new IllegalStateException("private-diagnostic-error"); }
            public void flush() {} public void close() {}
        });
        var audit = new AuditLogger(root, broken);
        assertDoesNotThrow(() -> emit(audit, "shop")); stop(audit);
        assertEquals(1, audit.stats().failed()); assertFalse(audit.stats().healthy());
        assertDoesNotThrow(() -> emit(audit, "after_stop")); assertEquals(1, audit.stats().rejected());
    }
    @Test void cooperativeConcurrentWriterIsRejectedAndNeverInterleavesWithOwner() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var owner = service(10, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {
            public void append(Path file, byte[] bytes) throws IOException { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { throw new IOException(failure); } AuditLogger.FileOps.super.append(file, bytes); }
        });
        try {
            emit(owner, "owner"); assertTrue(entered.await(5, TimeUnit.SECONDS)); var second = service(10, AuditLogger.MAX_BYTES, new AuditLogger.FileOps() {});
            emit(second, "second"); stop(second); assertEquals(1, second.stats().failed()); release.countDown(); stop(owner); assertEquals(1, entries().size());
        } finally { release.countDown(); stop(owner); }
    }
}
