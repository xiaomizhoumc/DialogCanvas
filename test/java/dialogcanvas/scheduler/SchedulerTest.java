package dialogcanvas.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {
    ServerMock server;
    JavaPlugin plugin;
    Scheduler scheduler;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); plugin = MockBukkit.createMockPlugin(); scheduler = new Scheduler(plugin);
    }
    @AfterEach void cleanup() { scheduler.close(); MockBukkit.unmock(); }

    @Test void inlineUserCodeDoesNotHoldRegistrationLock() {
        scheduler.sync(() -> {
            // Waiting is test-only: a worker must be able to register without the
            // currently executing main-thread callback releasing its lock first.
            assertDoesNotThrow(() -> CompletableFuture.runAsync(() -> scheduler.sync(() -> {}))
                    .get(2, TimeUnit.SECONDS));
        }).join();
        server.getScheduler().performOneTick();
    }
    @Test void disabledPluginAllowsImmediateCleanupButRejectsWorkerAndDelayedTasks() throws Exception {
        server.getPluginManager().disablePlugin(plugin);
        assertFalse(plugin.isEnabled());
        AtomicBoolean ran = new AtomicBoolean();
        scheduler.sync(() -> { assertTrue(Bukkit.isPrimaryThread()); ran.set(true); }).join();
        assertTrue(ran.get());
        var worker = CompletableFuture.supplyAsync(() -> scheduler.sync(() -> fail("Disabled task executed"))).get();
        assertThrows(CompletionException.class, worker::join);
        assertThrows(CompletionException.class, () -> scheduler.syncAfter(1, () -> fail("Delayed cleanup executed")).join());
        scheduler.close();
        assertThrows(CompletionException.class, () -> scheduler.sync(() -> fail("Closed scheduler executed")).join());
    }
    @Test void cancellationAndCloseSettlePendingFuturesWithoutExecutingGameWork() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        var scheduled = CompletableFuture.supplyAsync(() -> scheduler.syncAfter(100, () -> ran.set(true))).get();
        CompletableFuture.runAsync(() -> scheduled.cancel(false)).get();
        var closing = scheduler.syncAfter(100, () -> ran.set(true));
        scheduler.close();
        assertTrue(closing.isCancelled());
        server.getScheduler().performTicks(101);
        assertFalse(ran.get());
    }
}
