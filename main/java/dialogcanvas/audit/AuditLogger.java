package dialogcanvas.audit;

import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.AtomicFiles;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/** Fixed-schema best-effort audit, independent from committed business state and transaction journals. */
public final class AuditLogger implements AutoCloseable {
    public enum Event { MENU_CREATE, MENU_UPDATE, MENU_DELETE, YAML_IMPORT, YAML_IMPORT_BATCH,
        RETENTION_SCAN, RETENTION_ARCHIVE, RETENTION_RESTORE, PASSWORD_CHANGE, MANAGEMENT_COMMAND }
    public enum Result { SUCCESS, PARTIAL, FAILED, REJECTED, UNCERTAIN, SKIPPED }
    public record Origin(String source, String actor) {
        public Origin {
            if (!Set.of("web", "console", "player", "system").contains(source)) throw new IllegalArgumentException("Invalid audit source");
            if (!(Set.of("local-admin-session", "console", "system").contains(actor)
                    || actor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                throw new IllegalArgumentException("Invalid audit actor");
        }
        public static Origin web() { return new Origin("web", "local-admin-session"); }
        public static Origin system() { return new Origin("system", "system"); }
    }
    public final class Operation {
        private final UUID id; private final Event event; private final Origin origin; private final String target;
        private final AtomicBoolean completed = new AtomicBoolean();
        private Operation(UUID id, Event event, Origin origin, String target) { this.id = id; this.event = event; this.origin = origin; this.target = safeTarget(target); }
        public Operation child(Event event, String target) { return new Operation(id, event, origin, target); }
        public void finish(Result result, String code) { finish(result, code, null, null); }
        public void finish(Result result, String code, String before, String after) {
            if (completed.compareAndSet(false, true)) enqueue(new Entry(clock.instant().toString(), event, id.toString(), origin.source(), origin.actor(), target,
                    result, safeCode(code), safeRevision(before), safeRevision(after)));
        }
        public boolean completed() { return completed.get(); }
    }
    private record Entry(String time, Event eventType, String operationId, String source, String actor,
                         String target, Result result, String code, String beforeRevision, String afterRevision) {}
    public record Stats(long accepted, long written, long failed, long rejected, boolean stopped, boolean healthy) {}
    interface FileOps {
        default void append(Path file, byte[] bytes) throws IOException {
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(false);
            }
        }
        default void rotate(Path source, Path destination) throws IOException { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
    }
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private final Path directory, file;
    private final Logger logger; private final Clock clock; private final long maxBytes; private final FileOps io;
    private final ArrayBlockingQueue<Entry> queue;
    private final tools.jackson.databind.json.JsonMapper json = MenuJson.createMapper();
    private final AtomicLong accepted = new AtomicLong(), written = new AtomicLong(), failed = new AtomicLong(), rejected = new AtomicLong();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile boolean stopping;
    private volatile boolean healthy = true;
    private final boolean enabled;
    private final Thread writer;
    public AuditLogger(Path pluginDirectory, Logger logger) { this(pluginDirectory, logger, Clock.systemUTC(), 1024, MAX_BYTES, new FileOps() {}); }
    AuditLogger(Path pluginDirectory, Logger logger, Clock clock, int capacity, long maxBytes, FileOps io) {
        this.directory = pluginDirectory.toAbsolutePath().normalize().resolve("logs"); this.file = directory.resolve("audit.log");
        this.logger = Objects.requireNonNull(logger); this.clock = clock; this.maxBytes = maxBytes; this.io = io; enabled = true;
        if (capacity < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid audit bounds");
        queue = new ArrayBlockingQueue<>(capacity);
        writer = Thread.ofPlatform().daemon().name("DCMenu-audit").start(this::writeLoop);
    }
    private AuditLogger() {
        directory = file = null; logger = null; clock = Clock.systemUTC(); maxBytes = MAX_BYTES; io = null;
        queue = null; enabled = false; writer = null; closed.complete(null);
    }
    /** Only for standalone services/tests. The production plugin installs one shared enabled logger. */
    public static AuditLogger disabled() { return new AuditLogger(); }
    public Operation begin(Event event, Origin origin, String target) { return new Operation(UUID.randomUUID(), Objects.requireNonNull(event), Objects.requireNonNull(origin), target); }
    private static String safeTarget(String value) { return value != null && value.matches("[A-Za-z0-9_:./* -]{1,160}") ? value : "<invalid-target>"; }
    private static String safeCode(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{0,79}") ? value : "UNCLASSIFIED"; }
    private static String safeRevision(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}") ? value : null; }
    private synchronized void enqueue(Entry entry) {
        if (!enabled) return;
        if (stopping || !queue.offer(entry)) {
            healthy = false;
            rejected.incrementAndGet(); alarm(stopping ? "AUDIT_STOPPED" : "AUDIT_QUEUE_FULL", entry); return;
        }
        accepted.incrementAndGet();
    }
    private void alarm(String code, Entry entry) {
        // Never log Throwable messages, request values, target content or the serialized record on failure.
        String message = "DCMenu audit unavailable: " + code + (entry == null ? "" : "; operationId=" + entry.operationId + "; event=" + entry.eventType)
                + ". Business result is unchanged; do not retry committed operations. Audit record may be missing; inspect logs/audit.log.";
        try { logger.severe(message); }
        catch (RuntimeException diagnosticFailure) {
            healthy = false;
            try { Logger.getLogger("DialogCanvas").severe("AUDIT_DIAGNOSTIC_FAILED; " + message); }
            catch (RuntimeException unavailable) { /* Both platform diagnostic routes failed: health/counters remain failed; never change business state. */ }
        }
    }
    private void writeLoop() {
        FileChannel lockChannel = null; FileLock lock = null; boolean poisoned = false;
        try {
            try {
                AtomicFiles.createDirectories(directory); AtomicFiles.requireTarget(file);
                Path lockFile = directory.resolve(".audit.lock"); AtomicFiles.requireTarget(lockFile);
                lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                lock = lockChannel.tryLock(); if (lock == null) throw new IOException("Audit already open");
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !validExisting()) {
                    rotate(); logger.warning("DCMenu audit: AUDIT_INVALID_EXISTING_ROTATED; original bytes preserved, inspect logs/audit-*.log.");
                }
            } catch (Exception failure) { poisoned = true; healthy = false; alarm("AUDIT_INITIALIZATION_FAILED", null); }
            while (!stopping || !queue.isEmpty()) {
                Entry entry;
                try { entry = queue.poll(50, TimeUnit.MILLISECONDS); } catch (InterruptedException interrupted) { continue; }
                if (entry == null) continue;
                if (poisoned) { failed.incrementAndGet(); alarm("AUDIT_WRITER_FAILED", entry); continue; }
                try {
                    AtomicFiles.requireTarget(file);
                    byte[] bytes = (json.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
                    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0 && Files.size(file) + bytes.length > maxBytes) rotate();
                    AtomicFiles.requireTarget(file); io.append(file, bytes); written.incrementAndGet();
                } catch (Exception failure) {
                    // Partial append/failed rotation is ambiguous: stop appending, never retry or duplicate this event.
                    poisoned = true; healthy = false; failed.incrementAndGet(); alarm("AUDIT_WRITE_OR_ROTATION_FAILED", entry);
                }
            }
        } finally {
            try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); }
            catch (IOException failure) { healthy = false; alarm("AUDIT_CLOSE_FAILED", null); }
            closed.complete(null);
        }
    }
    private boolean validExisting() throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        // Fixed-size line accumulation: an arbitrary old/corrupt log cannot exhaust memory.
        try (var reader = new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), decoder)) {
            StringBuilder line = new StringBuilder(); int next;
            while ((next = reader.read()) != -1) {
                if (next == '\n') {
                    if (line.isEmpty() || !json.readTree(line.toString()).isObject()) return false;
                    line.setLength(0);
                } else { if (line.length() >= 16384) return false; line.append((char) next); }
            }
            return line.isEmpty();
        } catch (CharacterCodingException | RuntimeException malformed) { return false; }
    }
    private void rotate() throws IOException {
        AtomicFiles.requireTarget(file);
        Path history = directory.resolve("audit-" + clock.instant().toEpochMilli() + "-" + UUID.randomUUID() + ".log");
        AtomicFiles.requireTarget(history);
        if (Files.exists(history, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive collision");
        io.rotate(file, history); // No ordinary move/overwrite fallback. The process lock excludes cooperative writers.
    }
    public Stats stats() { return new Stats(accepted.get(), written.get(), failed.get(), rejected.get(), stopping, healthy); }
    public synchronized CompletableFuture<Void> closeAsync() { stopping = true; return closed.copy(); }
    @Override public void close() { closeAsync(); }
}
