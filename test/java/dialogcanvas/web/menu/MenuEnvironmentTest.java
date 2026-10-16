package dialogcanvas.web.menu;

import dialogcanvas.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MenuEnvironmentTest {
    @Test void capturesBukkitCapabilitiesThroughSchedulerAndRejectsWorkerCapture() {
        MockBukkit.mock();
        try (var scheduler = new Scheduler(MockBukkit.createMockPlugin())) {
            var captured = new AtomicReference<MenuEnvironment>();
            scheduler.sync(() -> captured.set(MenuEnvironment.capture())).join();
            assertTrue(captured.get().materials().contains("STONE"));
            assertTrue(captured.get().sounds().contains("ENTITY_PLAYER_LEVELUP"));
            assertThrows(UnsupportedOperationException.class, () -> captured.get().materials().clear());
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(MenuEnvironment::capture).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally { MockBukkit.unmock(); }
    }
}
