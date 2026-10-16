package dialogcanvas.dialog;

import dialogcanvas.model.MenuJson;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.web.menu.*;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MainMenuShortcutTest {
    @TempDir Path directory;
    ServerMock server; PlayerMock player; Scheduler scheduler; MenuRepository menus; MainMenuShortcut shortcut;
    AtomicInteger opened = new AtomicInteger(); CompletableFuture<Void> display = new CompletableFuture<>();
    @BeforeEach void setup() {
        server = MockBukkit.mock(); var plugin = MockBukkit.createMockPlugin(); scheduler = new Scheduler(plugin);
        player = server.addPlayer(); player.addAttachment(plugin, "dcmenu.shortcut", true);
        menus = new MenuRepository(new FileStorage(directory), new MenuValidator(new MenuEnvironment(Set.of("STONE"), Set.of())));
        menus.initialize().join();
        shortcut = new MainMenuShortcut(scheduler, () -> menus, (actor, id) -> {
            assertTrue(Bukkit.isPrimaryThread()); assertSame(player, actor); assertEquals("main", id); opened.incrementAndGet(); return display;
        });
    }
    @AfterEach void cleanup() { shortcut.close(); display.complete(null); menus.close(); scheduler.close(); MockBukkit.unmock(); }
    private void mainMenu() { menus.create(MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"main\",\"title\":\"主菜单\",\"dialogType\":\"notice\",\"shortcutEnabled\":true}")).join(); }
    private PlayerSwapHandItemsEvent event() { return new PlayerSwapHandItemsEvent(player, new ItemStack(Material.STONE), new ItemStack(Material.DIAMOND)); }
    @Test void onlySneakSwapWithCommittedMainCancelsVanillaAndOpensOnMainThread() {
        player.setSneaking(true); var empty = event(); shortcut.onSwap(empty); assertFalse(empty.isCancelled());
        mainMenu(); player.setSneaking(false); var regular = event(); shortcut.onSwap(regular); assertFalse(regular.isCancelled());
        player.setSneaking(true); var triggered = event(); shortcut.onSwap(triggered); assertTrue(triggered.isCancelled()); assertEquals(1, opened.get());
        assertEquals(Material.STONE, triggered.getMainHandItem().getType()); assertEquals(Material.DIAMOND, triggered.getOffHandItem().getType());
    }
    @Test void deniedCancelledClosedAndUnavailableEventsPreserveOtherBehavior() {
        mainMenu(); player.setSneaking(true); var cancelled = event(); cancelled.setCancelled(true); shortcut.onSwap(cancelled); assertEquals(0, opened.get());
        player.addAttachment(MockBukkit.createMockPlugin(), "dcmenu.shortcut", false); var denied = event(); shortcut.onSwap(denied); assertFalse(denied.isCancelled());
        player.addAttachment(MockBukkit.createMockPlugin(), "dcmenu.shortcut", true); menus.close(); var unavailable = event(); shortcut.onSwap(unavailable); assertFalse(unavailable.isCancelled());
        shortcut.close(); var stopped = event(); shortcut.onSwap(stopped); assertFalse(stopped.isCancelled()); assertEquals(0, opened.get());
    }
    @Test void pendingOpenIsDeduplicatedAndReleasedOnFailure() {
        mainMenu(); player.setSneaking(true); shortcut.onSwap(event()); var duplicate = event(); shortcut.onSwap(duplicate);
        assertTrue(duplicate.isCancelled()); assertEquals(1, opened.get()); display.completeExceptionally(new IllegalStateException("test failure"));
        display = CompletableFuture.completedFuture(null); shortcut.onSwap(event()); assertEquals(2, opened.get());
    }
}
