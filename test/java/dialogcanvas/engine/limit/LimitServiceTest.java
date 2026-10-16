package dialogcanvas.engine.limit;

import dialogcanvas.scheduler.Scheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static dialogcanvas.engine.limit.LimitService.*;

class LimitServiceTest {
    @TempDir Path directory;
    ServerMock server;
    Scheduler scheduler;
    LimitService service;
    MutableClock clock;
    final UUID uuid = UUID.randomUUID();
    @BeforeEach void setup() {
        server = MockBukkit.mock(); scheduler = new Scheduler(MockBukkit.createMockPlugin());
        clock = new MutableClock(Instant.parse("2026-10-10T12:00:00Z"));
        service = new LimitService(directory, scheduler, clock, ZoneId.of("Asia/Shanghai"));
    }
    @AfterEach void teardown() throws Exception {
        await(service.closeAsync()); scheduler.close(); MockBukkit.unmock();
    }
    Policy policy(Reset reset, long cooldown) { return new Policy("menu_shop/buy", 3, 2, reset, 60, cooldown); }
    @Test void failedDurableQuotaCommitRetainsReservationAndNeverRunsSuccessEffects() throws Exception {
        await(service.closeAsync());
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        service = new LimitService(directory, scheduler, clock, ZoneId.of("Asia/Shanghai"), (path, bytes) -> {
            if (writes.incrementAndGet() == 2) throw new java.io.IOException("injected settlement failure");
            dialogcanvas.storage.AtomicFiles.write(path, bytes);
        });
        var effects = new java.util.concurrent.atomic.AtomicBoolean();
        var result = service.executeManyDurably(uuid, List.of(policy(Reset.NEVER, 60)), () -> true, () -> true,
                commit -> commit.get().thenApply(ignored -> { effects.set(true); return Outcome.COMMITTED; }));
        assertThrows(ExecutionException.class, () -> await(result)); assertFalse(effects.get());
        String pending = Files.readString(directory.resolve("playerdata/" + uuid + ".yml"));
        assertTrue(pending.contains("pendingQuantity: 2")); assertFalse(pending.contains("cooldownUntil"));
        await(service.closeAsync()); service = new LimitService(directory, scheduler, clock, ZoneId.of("Asia/Shanghai"));
        assertEquals(Status.RECOVERY_REQUIRED, await(buy(policy(Reset.NEVER, 60))).status());
    }
    @Test void redirectedPlayerDataDirectoryIsRejectedBeforeExecution() throws Exception {
        Path outside = directory.resolve("outside"); Files.createDirectory(outside);
        dialogcanvas.storage.PersistenceTestLinks.redirectDirectory(directory.resolve("playerdata"), outside);
        var executed = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(ExecutionException.class, () -> await(service.execute(uuid, policy(Reset.NEVER, 0), () -> true, () -> true,
                () -> { executed.set(true); return CompletableFuture.completedFuture(Outcome.COMMITTED); })));
        assertFalse(executed.get());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }
    CompletableFuture<Result> buy(Policy policy) {
        return service.execute(uuid, policy, () -> true, () -> true,
                () -> CompletableFuture.completedFuture(Outcome.COMMITTED));
    }
    <T> T await(CompletableFuture<T> future) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && System.nanoTime() < end) {
            server.getScheduler().performOneTick(); Thread.sleep(2);
        }
        return future.get(1, TimeUnit.SECONDS);
    }
    @Test void quantityAndRestartPersistence() throws Exception {
        assertEquals(1, await(buy(policy(Reset.NEVER, 0))).remaining());
        assertTrue(Files.readString(directory.resolve("playerdata/" + uuid + ".yml")).contains("count: 2"));
        await(service.closeAsync()); service = new LimitService(directory, scheduler, clock, ZoneId.of("Asia/Shanghai"));
        assertEquals(Status.LIMIT_REACHED, await(buy(policy(Reset.NEVER, 0))).status());
    }
    @ParameterizedTest @CsvSource({
        "DAILY,2026-10-10T16:00:00Z", "WEEKLY,2026-10-11T16:00:00Z",
        "MONTHLY,2026-10-31T16:00:00Z", "TIMED,2026-10-10T12:01:00Z" })
    void resetAtExactBoundary(String reset, String boundary) throws Exception {
        Policy p = policy(Reset.valueOf(reset), 0); await(buy(p));
        clock.now = Instant.parse(boundary).minusNanos(1);
        assertEquals(Status.LIMIT_REACHED, await(buy(p)).status());
        clock.now = Instant.parse(boundary);
        assertEquals(Status.EXECUTED, await(buy(p)).status());
    }
    @Test void cooldownPersistsAcrossQuotaResetAndRestart() throws Exception {
        Policy p = new Policy("cooldown", 100, 1, Reset.DAILY, 0, 86400);
        await(buy(p)); await(service.closeAsync());
        service = new LimitService(directory, scheduler, clock, ZoneId.of("Asia/Shanghai"));
        clock.now = Instant.parse("2026-10-10T16:00:00Z");
        assertEquals(Status.COOLDOWN_ACTIVE, await(buy(p)).status());
        clock.now = Instant.parse("2026-10-11T12:00:00Z");
        assertEquals(Status.EXECUTED, await(buy(p)).status());
    }
    @Test void gateOrderAndMainThread() throws Exception {
        List<String> events = new ArrayList<>();
        Result r = await(service.execute(uuid, policy(Reset.NEVER, 0), () -> {
            assertTrue(org.bukkit.Bukkit.isPrimaryThread()); events.add("permission"); return true;
        }, () -> { events.add("condition"); return true; }, () -> {
            assertTrue(org.bukkit.Bukkit.isPrimaryThread()); events.add("execute");
            return CompletableFuture.completedFuture(Outcome.COMMITTED);
        }));
        assertEquals(Status.EXECUTED, r.status());
        assertEquals(List.of("permission", "condition", "execute"), events);
        assertEquals(Status.PERMISSION_DENIED, await(service.execute(uuid, policy(Reset.NEVER, 0),
                () -> false, () -> { fail("condition should be skipped"); return true; },
                () -> { fail("execution should be skipped"); return null; })).status());
        assertEquals(Status.CONDITION_DENIED, await(service.execute(uuid, policy(Reset.NEVER, 0),
                () -> true, () -> false, () -> { fail(); return null; })).status());
    }
    @Test void quotaCheckedBeforeCooldown() throws Exception {
        Policy p = policy(Reset.NEVER, 100); await(buy(p));
        assertEquals(Status.LIMIT_REACHED, await(buy(p)).status());
    }
    @Test void rollbackDoesNotConsumeOrStartCooldown() throws Exception {
        assertEquals(Status.EXECUTION_ROLLED_BACK, await(service.execute(uuid, policy(Reset.NEVER, 100),
                () -> true, () -> true, () -> CompletableFuture.completedFuture(Outcome.ROLLED_BACK))).status());
        assertEquals(Status.EXECUTED, await(buy(policy(Reset.NEVER, 100))).status());
    }
    @Test void uncertainExecutionBlocksAfterRestart() throws Exception {
        assertEquals(Status.RECOVERY_REQUIRED, await(service.execute(uuid, policy(Reset.NEVER, 0),
                () -> true, () -> true, () -> CompletableFuture.failedFuture(new RuntimeException("uncertain")))).status());
        await(service.closeAsync()); service = new LimitService(directory, scheduler, clock, ZoneId.of("Asia/Shanghai"));
        assertEquals(Status.RECOVERY_REQUIRED, await(buy(policy(Reset.NEVER, 0))).status());
    }
    @Test void concurrentClicksCannotOverbuy() throws Exception {
        CompletableFuture<Outcome> pending = new CompletableFuture<>();
        CompletableFuture<Result> first = service.execute(uuid, policy(Reset.NEVER, 0), () -> true, () -> true, () -> pending);
        CompletableFuture<Result> second = buy(policy(Reset.NEVER, 0));
        for (int i = 0; i < 30; i++) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        assertFalse(second.isDone()); pending.complete(Outcome.COMMITTED);
        assertEquals(Status.EXECUTED, await(first).status());
        assertEquals(Status.LIMIT_REACHED, await(second).status());
    }
    @Test void malformedFileFailsClosed() throws Exception {
        Files.createDirectories(directory.resolve("playerdata"));
        Files.writeString(directory.resolve("playerdata/" + uuid + ".yml"), "limits: invalid");
        assertThrows(ExecutionException.class, () -> await(buy(policy(Reset.NEVER, 0))));
    }
    @Test void neverDoesNotResetOverYears() throws Exception {
        await(buy(policy(Reset.NEVER, 0)));
        clock.now = Instant.parse("2030-01-01T00:00:00Z");
        assertEquals(Status.LIMIT_REACHED, await(buy(policy(Reset.NEVER, 0))).status());
    }
    @Test void dailyUsesLocalMidnightAcrossDaylightSaving() throws Exception {
        await(service.closeAsync());
        service = new LimitService(directory, scheduler, clock, ZoneId.of("America/New_York"));
        clock.now = Instant.parse("2026-03-08T05:00:00Z");
        await(buy(policy(Reset.DAILY, 0)));
        // This local day is 23 hours long.
        clock.now = Instant.parse("2026-03-09T03:59:59Z");
        assertEquals(Status.LIMIT_REACHED, await(buy(policy(Reset.DAILY, 0))).status());
        clock.now = Instant.parse("2026-03-09T04:00:00Z");
        assertEquals(Status.EXECUTED, await(buy(policy(Reset.DAILY, 0))).status());
    }
    @Test void reservationWriteFailurePreventsExecution() throws Exception {
        // A file occupying the directory makes the reservation impossible to persist.
        Files.writeString(directory.resolve("playerdata"), "blocked");
        assertThrows(ExecutionException.class, () -> await(service.execute(uuid, policy(Reset.NEVER, 0),
                () -> true, () -> true, () -> { fail("must not execute without durable reservation"); return null; })));
        assertEquals("blocked", Files.readString(directory.resolve("playerdata")));
    }
    @ParameterizedTest @CsvSource({
        "WEEKLY,2026-10-11T16:00:00Z,2026-10-18T16:00:00Z,Asia/Shanghai",
        "MONTHLY,2028-02-01T00:00:00Z,2028-03-01T00:00:00Z,UTC",
        "MONTHLY,2026-12-31T12:00:00Z,2027-01-01T00:00:00Z,UTC",
        "DAILY,2026-11-01T04:00:00Z,2026-11-02T05:00:00Z,America/New_York" })
    void calendarEdgeCases(String reset, String start, String end, String zone) throws Exception {
        await(service.closeAsync());
        service = new LimitService(directory, scheduler, clock, ZoneId.of(zone));
        clock.now = Instant.parse(start);
        Policy p = policy(Reset.valueOf(reset), 0); await(buy(p));
        clock.now = Instant.parse(end).minusNanos(1);
        assertEquals(Status.LIMIT_REACHED, await(buy(p)).status());
        clock.now = Instant.parse(end);
        assertEquals(Status.EXECUTED, await(buy(p)).status());
    }
    @Test void timedStartsAtCommitAndDoesNotSlideOnLaterPurchases() throws Exception {
        Policy p = new Policy("timed", 2, 1, Reset.TIMED, 60, 0);
        CompletableFuture<Outcome> execution = new CompletableFuture<>();
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Result> first = service.execute(uuid, p, () -> true, () -> true, () -> {
            entered.complete(null); return execution;
        });
        await(entered);
        clock.now = clock.now.plusSeconds(30); execution.complete(Outcome.COMMITTED); await(first);
        clock.now = clock.now.plusSeconds(30); await(buy(p));
        clock.now = Instant.parse("2026-10-10T12:01:29Z");
        assertEquals(Status.LIMIT_REACHED, await(buy(p)).status());
        clock.now = Instant.parse("2026-10-10T12:01:30Z");
        assertEquals(Status.EXECUTED, await(buy(p)).status());
    }
    @Test void purchaseCrossingMidnightCountsInNewPeriod() throws Exception {
        Policy p = new Policy("midnight", 3, 1, Reset.DAILY, 0, 0); await(buy(p));
        CompletableFuture<Outcome> execution = new CompletableFuture<>();
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Result> second = service.execute(uuid, p, () -> true, () -> true, () -> {
            entered.complete(null); return execution;
        });
        await(entered); clock.now = Instant.parse("2026-10-10T16:00:00Z");
        execution.complete(Outcome.COMMITTED);
        assertEquals(2, await(second).remaining());
    }
    @ParameterizedTest @CsvSource({"TIMED,9223372036854775807,0", "NEVER,0,9223372036854775807"})
    void overflowingIntervalsNeverExecute(String reset, long timed, long cooldown) {
        Policy p = new Policy("overflow", 3, 1, Reset.valueOf(reset), timed, cooldown);
        assertThrows(ExecutionException.class, () -> await(service.execute(uuid, p, () -> true, () -> true,
                () -> { fail("invalid time interval must be rejected before execution"); return null; })));
    }
    static class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
