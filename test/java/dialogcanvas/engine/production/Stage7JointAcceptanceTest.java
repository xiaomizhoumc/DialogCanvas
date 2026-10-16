package dialogcanvas.engine.production;

import dialogcanvas.engine.ClickGuard;
import dialogcanvas.engine.action.ActionServices;
import dialogcanvas.engine.limit.LimitService;
import dialogcanvas.model.*;
import dialogcanvas.placeholder.PlaceholderApiResolver;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.AtomicFiles;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Production services and real temporary files; fake Vault receipt, no native Dialog provider/Bot. */
class Stage7JointAcceptanceTest {
    @TempDir Path root;
    ServerMock server;
    PlayerMock player;
    UUID playerId;
    Scheduler scheduler;
    ProductionStore store;
    ProductionActionEngine engine;
    final ClickGuard guard = new ClickGuard();
    final Queue<Phase> phases = new ConcurrentLinkedQueue<>();
    final AtomicInteger withdrawals = new AtomicInteger();
    final AtomicInteger commands = new AtomicInteger();
    final CompletableFuture<ActionServices.Change> provider = new CompletableFuture<>();
    volatile ActionServices.Change receipt;
    volatile String scenario = "success";
    double balance = 10000;
    CountDownLatch receiptEntered, releaseReceipt;
    MenuDefinition menu;

    record Phase(String name, boolean main) {}
    void phase(String name) { phases.add(new Phase(name, Bukkit.isPrimaryThread())); }

    @BeforeEach void setup() {
        server = MockBukkit.mock(); player = server.addPlayer(); playerId = player.getUniqueId();
        scheduler = new Scheduler(MockBukkit.createMockPlugin());
        store = new ProductionStore(root.resolve("data"), (path, bytes) -> {
            assertFalse(Bukkit.isPrimaryThread(), "State file writes must be off main");
            String category = path.getParent().getFileName().toString();
            if (category.equals("player-data") && scenario.equals("playerdata")) throw new IOException("Injected player data failure");
            if (category.equals("transactions")) {
                String state = MenuJson.createMapper().readTree(bytes).path("status").asString();
                if (scenario.equals("intent") && state.equals("pending")) throw new IOException("Injected intent failure");
                if (scenario.equals("checkpoint") && state.equals("effectsPending")) throw new IOException("Injected checkpoint failure");
                if (scenario.equals("receipt") && state.equals("committed")) throw new IOException("Injected receipt failure");
                if (state.equals("effectsPending")) {
                    assertTrue(quota().contains("count: 1")); assertFalse(quota().contains("pendingQuantity"));
                    phase("quotaCommitted");
                }
                if (state.equals("committed") && releaseReceipt != null) {
                    receiptEntered.countDown();
                    try { assertTrue(releaseReceipt.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                }
                AtomicFiles.write(path, bytes); phase(state);
            } else {
                AtomicFiles.write(path, bytes); phase("playerDataPersisted");
            }
        });
        store.initialize().join();
        var log = Logger.getAnonymousLogger(); log.setUseParentHandlers(false);
        var placeholders = new PlaceholderApiResolver(log, () -> {
            assertTrue(Bukkit.isPrimaryThread()); assertEquals("pending", journal().path("status").asString());
            assertFalse(Files.exists(root.resolve("playerdata/" + playerId + ".yml")));
            phase("condition"); return !scenario.equals("placeholder");
        }, (p, token) -> "10000");
        var money = new ActionServices.Economy() {
            public CompletableFuture<ActionServices.Change> deposit(UUID id, double amount) { throw new AssertionError("Unexpected deposit"); }
            public CompletableFuture<ActionServices.Change> withdraw(UUID id, double amount) {
                assertTrue(Bukkit.isPrimaryThread()); assertEquals(playerId, id);
                assertTrue(quota().contains("pendingQuantity: 1")); phase("quotaReserved");
                withdrawals.incrementAndGet(); balance -= amount; phase("withdraw");
                receipt = new ActionServices.Change() {
                    public CompletableFuture<Void> rollback() {
                        assertTrue(Bukkit.isPrimaryThread()); balance += amount; phase("rollback");
                        return CompletableFuture.completedFuture(null);
                    }
                    public Object recoveryData() { return Map.of("amount", amount); }
                };
                return provider;
            }
        };
        engine = new ProductionActionEngine(scheduler, store, new LimitService(root, scheduler), placeholders,
                new ActionServices.Menus() {
                    public CompletableFuture<Void> open(org.bukkit.entity.Player p, String id) { throw new AssertionError(); }
                    public CompletableFuture<Void> refresh(org.bukkit.entity.Player p, MenuDefinition m) { throw new AssertionError(); }
                }, log, money);
        server.getCommandMap().register("stage7", new Command("jointcallback") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                assertTrue(Bukkit.isPrimaryThread()); assertSame(player, sender); assertTrue(player.isOp());
                assertEquals(1, player.getInventory().getItem(0).getAmount());
                assertEquals(1, store.find(playerId, "joint.order").orElseThrow().asInt());
                assertEquals("effectsPending", journal().path("status").asString());
                assertTrue(quota().contains("count: 1")); assertFalse(quota().contains("pendingQuantity"));
                commands.incrementAndGet(); phase("sideEffect");
                if (scenario.equals("opFailure")) throw new IllegalStateException("Injected target command failure");
                return true;
            }
        });
        menu = MenuJson.createMapper().readValue("""
            {"schemaVersion":1,"id":"joint","title":"联合验收","dialogType":"multi_action",
             "actions":[{"id":"buy","label":"购买","conditions":{"type":"placeholder","placeholder":"%balance%","operator":">=","value":"1000"},
              "actions":[{"type":"economy_withdraw","amount":"1000"},{"type":"give_item","material":"DIAMOND_SWORD","amount":"1"},
               {"type":"player_data_set","key":"joint.order","value":1},{"type":"command","command":"jointcallback","executor":"op"},
               {"type":"message","text":"联合交易完成"}],
              "denyActions":[{"type":"economy_withdraw","amount":"1"}],"limit":{"buy":{"player":3,"reset":"DAILY"}},"cooldown":{"buy":10}}]}
            """, MenuDefinition.class);
    }

    JsonNode journal() {
        try (var files = Files.list(root.resolve("data/transactions"))) {
            return MenuJson.createMapper().readTree(Files.readAllBytes(files.findFirst().orElseThrow()));
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    String quota() {
        try { return Files.readString(root.resolve("playerdata/" + playerId + ".yml")); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    CompletableFuture<Boolean> clickFromWorker() {
        return CompletableFuture.supplyAsync(() -> {
            assertFalse(Bukkit.isPrimaryThread()); phase("asyncClick");
            return guard.execute(playerId, menu.getId(), "buy", () -> engine.executeAsync(player, menu, menu.getActions().getFirst(), null));
        }).thenCompose(value -> value);
    }
    <T> T await(CompletableFuture<T> future) throws Exception {
        until(future::isDone); return future.join();
    }
    void until(java.util.function.BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.getAsBoolean() && System.nanoTime() < end) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        assertTrue(check.getAsBoolean(), "Joint chain did not reach checkpoint");
    }
    void completeProviderFromWorker() {
        CompletableFuture.runAsync(() -> { assertFalse(Bukkit.isPrimaryThread()); phase("providerReceipt"); provider.complete(receipt); }).join();
    }
    void assertBlockedOnRestart(boolean expected) throws Exception {
        await(engine.closeAsync());
        try (var restarted = new ProductionStore(root.resolve("data"))) {
            restarted.initialize().join(); assertEquals(expected, restarted.blocked(playerId));
        }
    }
    @AfterEach void cleanup() throws Exception {
        if (releaseReceipt != null) releaseReceipt.countDown();
        try { if (engine != null) await(engine.closeAsync()); }
        finally { scheduler.close(); MockBukkit.unmock(); }
    }

    @Test void asynchronousClickCommitsAllStateBeforeCompletionAndRejectsConcurrentDuplicates() throws Exception {
        receiptEntered = new CountDownLatch(1); releaseReceipt = new CountDownLatch(1);
        var first = clickFromWorker();
        var completion = first.thenRun(() -> {
            // A completion callback may run on the file worker: inspect only pure/file state here.
            assertEquals("committed", journal().path("status").asString());
            assertFalse(store.blocked(playerId)); phase("completion");
        });
        until(() -> receipt != null);
        for (int i = 0; i < 20; i++) assertFalse(await(clickFromWorker()));
        assertEquals(1, withdrawals.get()); assertFalse(first.isDone()); assertEquals(0, commands.get());
        completeProviderFromWorker(); until(() -> receiptEntered.getCount() == 0);
        assertFalse(first.isDone()); assertEquals("effectsPending", journal().path("status").asString());
        assertFalse(await(clickFromWorker())); assertFalse(player.isOp());
        releaseReceipt.countDown(); assertTrue(await(first)); await(completion);
        assertEquals(9000, balance); assertEquals(1, withdrawals.get()); assertEquals(1, commands.get());
        assertEquals(Material.DIAMOND_SWORD, player.getInventory().getItem(0).getType());
        assertEquals("联合交易完成", player.nextMessage()); assertFalse(store.blocked(playerId));
        var order = phases.stream().map(Phase::name).toList();
        String previous = "asyncClick";
        for (String next : List.of("pending", "condition", "quotaReserved", "withdraw", "providerReceipt", "playerDataPersisted", "quotaCommitted", "effectsPending", "sideEffect", "committed", "completion")) {
            assertTrue(order.indexOf(previous) < order.indexOf(next), previous + " before " + next); previous = next;
        }
        for (var event : phases) {
            if (Set.of("pending", "providerReceipt", "playerDataPersisted", "quotaCommitted", "effectsPending", "committed", "asyncClick").contains(event.name())) assertFalse(event.main(), event.name());
            if (Set.of("condition", "quotaReserved", "withdraw", "sideEffect").contains(event.name())) assertTrue(event.main(), event.name());
        }
        assertTrue(guard.execute(playerId, "joint", "buy", () -> CompletableFuture.completedFuture(null)).join());
        assertBlockedOnRestart(false);
    }

    @ParameterizedTest(name = "jointBoundary[{index}] {0}")
    @ValueSource(strings = {"placeholder", "intent", "playerdata", "checkpoint", "receipt", "offline", "stop", "opFailure"})
    void jointFailureBoundaryPreservesAccountingAndPrivilege(String mode) throws Exception {
        scenario = mode;
        var first = clickFromWorker();
        if (!Set.of("placeholder", "intent").contains(mode)) {
            until(() -> receipt != null);
            assertFalse(await(clickFromWorker())); assertFalse(first.isDone());
            if (mode.equals("offline")) player.disconnect();
            if (mode.equals("stop")) await(engine.closeAsync());
            completeProviderFromWorker();
        }
        if (Set.of("intent", "receipt").contains(mode)) assertThrows(CompletionException.class, () -> await(first));
        else assertTrue(await(first));
        assertFalse(player.isOp(), "Temporary OP must be restored even on command failure");
        boolean beforeCharge = Set.of("placeholder", "intent").contains(mode);
        boolean rolledBack = Set.of("playerdata", "offline").contains(mode);
        boolean uncertain = Set.of("checkpoint", "receipt", "stop").contains(mode);
        assertEquals(beforeCharge ? 0 : 1, withdrawals.get());
        assertEquals(beforeCharge || rolledBack ? 10000 : 9000, balance);
        assertEquals(Set.of("receipt", "opFailure").contains(mode) ? 1 : 0, commands.get());
        assertEquals(uncertain, store.blocked(playerId));
        var messages = new ArrayList<String>(); String message;
        while ((message = player.nextMessage()) != null) messages.add(message);
        assertEquals(mode.equals("receipt"), messages.contains("联合交易完成"));
        if (beforeCharge) assertFalse(Files.exists(root.resolve("playerdata/" + playerId + ".yml")));
        if (!mode.equals("intent")) {
            assertEquals(switch (mode) {
                case "placeholder" -> "conditionError";
                case "playerdata", "offline" -> "rolledBack";
                case "receipt" -> "effectsPending";
                case "opFailure" -> "committed";
                default -> "recoveryRequired";
            }, journal().path("status").asString());
        }
        if (mode.equals("stop")) {
            server.getScheduler().performTicks(2); assertNull(player.getInventory().getItem(0), "Late provider must not deliver after stop");
        }
        assertBlockedOnRestart(uncertain);
        assertTrue(guard.execute(playerId, "joint", "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
}
