package dialogcanvas.dialog;

import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.web.menu.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class SavedMenuOpenerTest {
    @TempDir Path directory;
    ServerMock server;
    Scheduler scheduler;
    MenuRepository menus;

    @BeforeEach void initialize() {
        server = MockBukkit.mock();
        scheduler = new Scheduler(MockBukkit.createMockPlugin());
        menus = new MenuRepository(new FileStorage(directory),
                new MenuValidator(new MenuEnvironment(Set.of("STONE"), Set.of())));
        menus.initialize().join();
    }
    @AfterEach void cleanup() { menus.close(); scheduler.close(); MockBukkit.unmock(); }
    void await(CompletableFuture<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            server.getScheduler().performTicks(1); Thread.sleep(5);
        }
        assertTrue(future.isDone(), "Main-thread completion timed out"); future.join();
    }
    tools.jackson.databind.JsonNode document(String title) {
        return MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"saved\",\"title\":\"" + title + "\",\"dialogType\":\"notice\"}");
    }
    @Test void opensLatestCommittedSnapshotOnMainThreadWithoutRestart() throws Exception {
        var player = server.addPlayer();
        var shown = new AtomicReference<MenuDefinition>();
        var opener = new SavedMenuOpener(scheduler, menus::definition, (actor, menu) -> {
            assertTrue(Bukkit.isPrimaryThread()); assertSame(player, actor); shown.set(menu);
            return CompletableFuture.completedFuture(null);
        }, Logger.getAnonymousLogger());
        var created = menus.create(document("first")).join();
        await(opener.open(player, "saved"));
        assertEquals("first", MenuJson.createMapper().valueToTree(shown.get()).get("title").asString());
        menus.update("saved", created.get("revision").asString(), document("updated")).join();
        await(opener.open(player, "saved"));
        assertEquals("updated", MenuJson.createMapper().valueToTree(shown.get()).get("title").asString());
    }
    @Test void unknownAndInvalidIdsReportPublicErrorsWithoutInvokingRenderer() throws Exception {
        var player = server.addPlayer();
        var opener = new SavedMenuOpener(scheduler, menus::definition, (actor, menu) -> {
            fail("An invalid menu must not be displayed"); return CompletableFuture.completedFuture(null);
        }, Logger.getAnonymousLogger());
        assertThrows(CompletionException.class, () -> await(opener.open(player, "missing"))); assertTrue(player.nextMessage().contains("找不到菜单"));
        assertThrows(CompletionException.class, () -> await(opener.open(player, "../escape"))); assertTrue(player.nextMessage().contains("菜单标识不合法"));
    }
    @Test void playerDisconnectDuringAsyncLookupSuppressesDisplay() throws Exception {
        Player player = new PlayerMock(server, "offline") {
            @Override public boolean isOnline() { assertTrue(Bukkit.isPrimaryThread()); return false; }
        };
        var lookup = new CompletableFuture<MenuDefinition>();
        var opener = new SavedMenuOpener(scheduler, ignored -> lookup, (actor, menu) -> {
            fail("Disconnected player must not be displayed"); return CompletableFuture.completedFuture(null);
        }, Logger.getAnonymousLogger());
        var opened = opener.open(player, "saved");
        CompletableFuture.runAsync(() -> lookup.complete(new MenuDefinition())).join();
        assertThrows(CompletionException.class, () -> await(opened));
    }
    @Test void unavailableRepositoryReportsOnMainThread() throws Exception {
        var notified = new java.util.concurrent.atomic.AtomicBoolean();
        Player player = new PlayerMock(server, "online") {
            @Override public boolean isOnline() { assertTrue(Bukkit.isPrimaryThread()); return true; }
            @Override public void sendMessage(String message) {
                assertTrue(Bukkit.isPrimaryThread()); assertTrue(message.contains("菜单服务尚未就绪"));
                notified.set(true);
            }
        };
        menus.close();
        var opener = new SavedMenuOpener(scheduler, menus::definition, (actor, menu) -> {
            fail("Unavailable repository must not render"); return CompletableFuture.completedFuture(null);
        }, Logger.getAnonymousLogger());
        assertThrows(CompletionException.class, () -> await(opener.open(player, "saved"))); assertTrue(notified.get());
    }
    @Test void workerDisplayFailureIsReportedOnMainAndPropagatedToActionCaller() throws Exception {
        var display = new CompletableFuture<Void>();
        var player = server.addPlayer();
        var cause = new IllegalStateException("display failed");
        var opener = new SavedMenuOpener(scheduler, id -> CompletableFuture.completedFuture(new MenuDefinition()),
                (actor, menu) -> { assertTrue(Bukkit.isPrimaryThread()); return display; }, Logger.getAnonymousLogger());
        var opened = opener.open(player, "saved");
        CompletableFuture.runAsync(() -> display.completeExceptionally(cause)).get();
        var failure = assertThrows(CompletionException.class, () -> await(opened));
        assertSame(cause, failure.getCause());
        assertTrue(player.nextMessage().contains("菜单显示失败"));
    }
}
