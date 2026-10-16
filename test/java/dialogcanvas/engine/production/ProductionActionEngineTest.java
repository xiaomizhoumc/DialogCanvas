package dialogcanvas.engine.production;

import dialogcanvas.engine.action.ActionServices;
import dialogcanvas.engine.condition.PlaceholderResolver;
import dialogcanvas.engine.limit.LimitService;
import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import io.papermc.paper.dialog.DialogResponseView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ProductionActionEngineTest {
    @TempDir Path root;
    ServerMock server; PlayerMock player; Scheduler scheduler; ProductionStore store; ProductionActionEngine engine;
    LimitService playerLimits;
    PlaceholderResolver resolverDelegate;
    double balance = 10000; boolean refundFails, placeholderFails, depositFails;
    boolean checkpointWriteFails, finalReceiptFails, recoveryWriteFails, conditionReceiptFails;
    String privateWriteFailure;
    Logger engineLogger;
    CompletableFuture<ActionServices.Change> heldWithdrawal;
    ActionServices.Change heldReceipt;
    java.util.concurrent.atomic.AtomicInteger withdrawalCalls = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch finalReceiptEntered, releaseFinalReceipt;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); player = server.addPlayer(); scheduler = new Scheduler(MockBukkit.createMockPlugin());
        store = new ProductionStore(root.resolve("data"), (path, bytes) -> {
            if (recoveryWriteFails && path.getParent().getFileName().toString().equals("recovery"))
                throw new java.io.IOException("injected recovery write failure");
            if (path.getParent().getFileName().toString().equals("transactions")) {
                if (privateWriteFailure != null) throw new java.io.IOException(privateWriteFailure);
                String status = MenuJson.createMapper().readTree(bytes).path("status").asString();
                if (conditionReceiptFails && status.equals("conditionError")) throw new java.io.IOException("injected condition receipt failure");
                if (checkpointWriteFails && status.equals("effectsPending")) throw new java.io.IOException("injected checkpoint failure");
                if (finalReceiptFails && status.equals("committed")) throw new java.io.IOException("injected final receipt failure");
                if (releaseFinalReceipt != null && status.equals("committed")) {
                    finalReceiptEntered.countDown();
                    try {
                        if (!releaseFinalReceipt.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test receipt gate timed out");
                    } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                }
            }
            dialogcanvas.storage.AtomicFiles.write(path, bytes);
        }); store.initialize().join();
        var money = new ActionServices.Economy() {
            public CompletableFuture<ActionServices.Change> deposit(UUID id, double amount) {
                return depositFails ? CompletableFuture.failedFuture(new IllegalStateException("Deposit rejected")) : change(amount);
            }
            public CompletableFuture<ActionServices.Change> withdraw(UUID id, double amount) {
                withdrawalCalls.incrementAndGet();
                if (balance < amount) return CompletableFuture.failedFuture(new dialogcanvas.engine.action.InsufficientFundsException());
                if (heldWithdrawal != null && id.equals(player.getUniqueId())) {
                    heldReceipt = change(-amount).join();
                    return heldWithdrawal;
                }
                return change(-amount);
            }
            private CompletableFuture<ActionServices.Change> change(double delta) {
                assertTrue(Bukkit.isPrimaryThread()); balance += delta;
                return CompletableFuture.completedFuture(new ActionServices.Change() {
                    public CompletableFuture<Void> rollback() {
                        assertTrue(Bukkit.isPrimaryThread());
                        if (refundFails) return CompletableFuture.failedFuture(new IllegalStateException("Refund failed"));
                        balance -= delta; return CompletableFuture.completedFuture(null);
                    }
                    public Object recoveryData() { return Map.of("amount", delta); }
                });
            }
        };
        resolverDelegate = (p, text) -> placeholderFails ? Optional.empty() : Optional.of(Double.toString(balance));
        PlaceholderResolver papi = new PlaceholderResolver() {
            public Optional<String> resolve(org.bukkit.entity.Player p, String text) { return resolverDelegate.resolve(p, text); }
            public Result resolveResult(org.bukkit.entity.Player p, String text) { return resolverDelegate.resolveResult(p, text); }
        };
        playerLimits = new LimitService(root, scheduler);
        engineLogger = Logger.getAnonymousLogger();
        engine = new ProductionActionEngine(scheduler, store, playerLimits, papi,
                new ActionServices.Menus() {
                    public CompletableFuture<Void> open(org.bukkit.entity.Player p, String id) { return CompletableFuture.completedFuture(null); }
                    public CompletableFuture<Void> refresh(org.bukkit.entity.Player p, MenuDefinition m) { return CompletableFuture.completedFuture(null); }
                }, engineLogger, money);
    }
    @AfterEach void cleanup() {
        if (releaseFinalReceipt != null) releaseFinalReceipt.countDown();
        engine.close(); scheduler.close(); MockBukkit.unmock();
    }

    MenuDefinition auditMenu() {
        var menu = menu(); menu.setInputs(null);
        var button = menu.getActions().getFirst(); button.setConditions(null);
        ((ActionStepDefinition.EconomyWithdraw) button.getActions().getFirst()).setAmount("1000");
        ((ActionStepDefinition.GiveItem) button.getActions().get(1)).setAmount("1");
        return menu;
    }
    @Test void conditionFailWithoutDenyActionsReportsRequirementWithoutWarningsOrSuccessEffects() throws Exception {
        var menu = auditMenu(); var button = menu.getActions().getFirst();
        button.setConditions(MenuJson.createMapper().readValue("{\"type\":\"permission\",\"permission\":\"test.never.granted\"}", ConditionDefinition.class));
        button.setDenyActions(List.of());
        var warnings = new java.util.concurrent.ConcurrentLinkedQueue<java.util.logging.LogRecord>();
        engineLogger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { warnings.add(record); }
            public void flush() {} public void close() {}
        });
        await(engine.executeAsync(player, menu, button, null));
        assertTrue(player.nextMessage().contains("未满足使用条件"));
        assertEquals(0, withdrawalCalls.get()); assertEquals(0, swords());
        assertFalse(store.blocked(player.getUniqueId())); assertTrue(warnings.isEmpty());
    }
    @Test void failedDenialSideEffectReportsCommitWithoutReplayingOrClaimingRollback() throws Exception {
        var menu = auditMenu(); var button = menu.getActions().getFirst();
        button.setConditions(MenuJson.createMapper().readValue("{\"type\":\"permission\",\"permission\":\"test.never.granted\"}", ConditionDefinition.class));
        button.setDenyActions(List.of(MenuJson.createMapper().readValue("{\"type\":\"command\",\"command\":\"missing_test_command\",\"executor\":\"console\",\"arguments\":[]}", ActionStepDefinition.class)));
        await(engine.executeAsync(player, menu, button, null));
        String message = player.nextMessage(); assertNotNull(message); assertTrue(message.contains("已提交"));
        assertFalse(message.contains("回滚")); assertTrue(message.contains("勿重复"));
        assertEquals(0, withdrawalCalls.get()); assertEquals(0, swords()); assertFalse(store.blocked(player.getUniqueId()));
        try (var files = Files.list(root.resolve("data/transactions"))) {
            var receipts = files.filter(file -> file.toString().endsWith(".json")).toList(); assertEquals(1, receipts.size());
            assertEquals("committed", MenuJson.createMapper().readTree(Files.readAllBytes(receipts.getFirst())).path("status").asString());
        }
    }
    void until(java.util.function.BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.getAsBoolean() && System.nanoTime() < deadline) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        assertTrue(check.getAsBoolean(), "Audit checkpoint not reached");
    }
    long transactionFiles() throws Exception {
        try (var files = Files.list(root.resolve("data/transactions"))) { return files.count(); }
    }
    @Test void failedProductionOperationLogsFixedDiagnosticWithoutPersistencePayload() throws Exception {
        var records = new java.util.concurrent.ConcurrentLinkedQueue<java.util.logging.LogRecord>();
        engineLogger.setUseParentHandlers(false);
        engineLogger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { records.add(record); }
            public void flush() {} public void close() {}
        });
        privateWriteFailure = "fixture-private-persistence-payload";
        var menu = auditMenu();
        assertThrows(CompletionException.class, () -> await(engine.executeAsync(player, menu, menu.getActions().getFirst(), null)));
        assertEquals(0, withdrawalCalls.get()); assertFalse(player.isOp());
        assertFalse(records.isEmpty());
        assertTrue(records.stream().noneMatch(record -> record.getMessage().contains(privateWriteFailure) || record.getThrown() != null));
        assertTrue(records.stream().anyMatch(record -> record.getMessage().contains(player.getUniqueId().toString())));
    }
    @Test void retentionCannotRaceProductionPurchaseAndOnlyExpiredStateIsArchivedAfterSettlement() throws Exception {
        Path file = root.resolve("playerdata/" + player.getUniqueId() + ".yml"); Files.createDirectories(file.getParent());
        Files.writeString(file, """
                limits:
                  old:
                    count: 3
                    resetMode: TIMED
                    lastCommittedAt: '2020-01-01T00:00:00Z'
                    resetAt: '2020-01-02T00:00:00Z'
                    cooldownUntil: '2020-01-01T00:00:00Z'
                """);
        var retention = new dialogcanvas.engine.retention.PlayerDataRetention(root, root.resolve("data"), playerLimits, store,
                new dialogcanvas.engine.retention.RetentionConfig(false, false, false, false, 0, 2), Logger.getAnonymousLogger());
        try {
            var menu = auditMenu(); menu.getActions().getFirst().getLimit().get("buy").setReset("NEVER"); heldWithdrawal = new CompletableFuture<>();
            var purchase = engine.executeAsync(player, menu, menu.getActions().getFirst(), null);
            until(() -> heldReceipt != null); byte[] pending = Files.readAllBytes(file);
            var during = retention.run(dialogcanvas.engine.retention.PlayerDataRetention.Mode.ARCHIVE, player.getUniqueId()).get(5, TimeUnit.SECONDS);
            assertEquals(0, during.archived()); assertTrue(during.reasons().containsKey("TRANSACTION_UNRESOLVED"));
            assertArrayEquals(pending, Files.readAllBytes(file));
            heldWithdrawal.complete(heldReceipt); await(purchase);
            var after = retention.run(dialogcanvas.engine.retention.PlayerDataRetention.Mode.ARCHIVE, player.getUniqueId()).get(5, TimeUnit.SECONDS);
            assertEquals(1, after.archived()); assertEquals(1, after.reasons().get("PERMANENT"));
            assertEquals(9000, balance); assertEquals(1, swords());
            String state = Files.readString(file); assertFalse(state.contains("old:")); assertTrue(state.contains("count: 1"));
            assertTrue(state.contains("resetMode: NEVER")); assertFalse(state.contains("pendingQuantity"));
        } finally { retention.closeAsync().get(5, TimeUnit.SECONDS); }
    }
    @Test void clickGuardCoversWithdrawDeliveryQuotaAndFinalDurableReceipt() throws Exception {
        var guard = new dialogcanvas.engine.ClickGuard(); var menu = auditMenu(); var button = menu.getActions().getFirst();
        heldWithdrawal = new CompletableFuture<>();
        finalReceiptEntered = new CountDownLatch(1); releaseFinalReceipt = new CountDownLatch(1);
        var first = guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null));
        until(() -> heldReceipt != null);
        for (int i = 0; i < 20; i++) assertFalse(guard.execute(player.getUniqueId(), menu.getId(), button.getId(),
                () -> { fail("duplicate entered engine"); return null; }).join());
        assertEquals(1, withdrawalCalls.get()); assertEquals(9000, balance); assertEquals(0, swords());
        String reserved = Files.readString(root.resolve("playerdata/" + player.getUniqueId() + ".yml"));
        assertTrue(reserved.contains("pendingQuantity: 1"));
        heldWithdrawal.complete(heldReceipt);
        until(() -> finalReceiptEntered.getCount() == 0);
        assertEquals(1, swords()); assertFalse(first.isDone());
        assertFalse(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> { fail(); return null; }).join());
        String committed = Files.readString(root.resolve("playerdata/" + player.getUniqueId() + ".yml"));
        assertTrue(committed.contains("count: 1")); assertFalse(committed.contains("pendingQuantity"));
        assertEquals(1, transactionFiles()); releaseFinalReceipt.countDown(); assertTrue(await(first));
        assertFalse(store.blocked(player.getUniqueId()));
        assertTrue(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> CompletableFuture.completedFuture(null)).join());
    }
    @Test void cancellationAndNewGuardCannotBypassPlayerWideAdmissionWhileOtherPlayerCanBuy() throws Exception {
        var guard = new dialogcanvas.engine.ClickGuard(); var menu = auditMenu(); var button = menu.getActions().getFirst();
        heldWithdrawal = new CompletableFuture<>();
        var first = guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null));
        until(() -> heldReceipt != null); first.cancel(false);
        assertFalse(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> { fail(); return null; }).join());
        var different = auditMenu(); different.setId("another_menu");
        await(engine.executeAsync(player, different, different.getActions().getFirst(), null));
        await(new dialogcanvas.engine.ClickGuard().execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null)));
        assertEquals(1, withdrawalCalls.get()); assertEquals(1, transactionFiles());
        var other = server.addPlayer();
        await(guard.execute(other.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(other, menu, button, null)));
        assertEquals(2, withdrawalCalls.get()); assertEquals(1, other.getInventory().getItem(0).getAmount());
        assertEquals(0, swords()); assertEquals(2, transactionFiles());
        heldWithdrawal.complete(heldReceipt);
        until(() -> !store.blocked(player.getUniqueId())); assertEquals(1, swords()); assertEquals(8000, balance);
    }
    @Test void uncertainTransactionRemainsBlockedAfterGuardUnlockAndStoreRestart() throws Exception {
        var menu = auditMenu(); var button = menu.getActions().getFirst();
        var guard = new dialogcanvas.engine.ClickGuard(); finalReceiptFails = true;
        var first = guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null));
        assertThrows(CompletionException.class, () -> await(first));
        assertTrue(store.blocked(player.getUniqueId())); assertEquals(1, swords());
        await(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null)));
        assertEquals(1, withdrawalCalls.get()); assertEquals(1, transactionFiles());
        try (var restarted = new ProductionStore(root.resolve("data"))) {
            restarted.initialize().join(); assertTrue(restarted.blocked(player.getUniqueId()));
        }
    }
    @Test void shutdownSettlesGuardWithoutResumingLateProviderAndKeepsRecoveryBlock() throws Exception {
        var menu = auditMenu(); var button = menu.getActions().getFirst(); var guard = new dialogcanvas.engine.ClickGuard();
        heldWithdrawal = new CompletableFuture<>();
        var first = guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null));
        until(() -> heldReceipt != null);
        var closed = engine.closeAsync();
        await(first); await(closed);
        assertTrue(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> CompletableFuture.completedFuture(null)).join());
        heldWithdrawal.complete(heldReceipt); server.getScheduler().performTicks(2);
        assertEquals(0, swords()); assertEquals(9000, balance);
        try (var restarted = new ProductionStore(root.resolve("data"))) {
            restarted.initialize().join(); assertTrue(restarted.blocked(player.getUniqueId()));
        }
    }
    @Test void guardedDenialBranchCannotDuplicateSideEffectsAndDoesNotReservePurchaseQuota() throws Exception {
        var menu = auditMenu(); var button = menu.getActions().getFirst();
        button.setConditions(MenuJson.createMapper().readValue("""
            {"operator":"AND","items":[{"type":"string","operator":"==",
             "left":{"source":"literal","value":"a"},"right":{"source":"literal","value":"b"}}]}
            """, ConditionDefinition.class));
        button.setDenyActions(button.getActions());
        heldWithdrawal = new CompletableFuture<>(); var guard = new dialogcanvas.engine.ClickGuard();
        var first = guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null));
        until(() -> heldReceipt != null);
        assertFalse(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> { fail(); return null; }).join());
        heldWithdrawal.complete(heldReceipt); await(first);
        assertEquals(1, withdrawalCalls.get()); assertEquals(1, swords()); assertEquals(9000, balance);
        assertFalse(Files.exists(root.resolve("playerdata/" + player.getUniqueId() + ".yml")), "Denied purchase does not reserve buy quota");
    }
    @Test void offlineDoesNotUnlockBeforeReceiptAndCompleteRollbackReleasesGuardAndReservation() throws Exception {
        var menu = auditMenu(); var button = menu.getActions().getFirst(); var guard = new dialogcanvas.engine.ClickGuard();
        heldWithdrawal = new CompletableFuture<>();
        var first = guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> engine.executeAsync(player, menu, button, null));
        until(() -> heldReceipt != null); player.disconnect();
        assertFalse(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> { fail(); return null; }).join());
        assertFalse(first.isDone()); heldWithdrawal.complete(heldReceipt); await(first);
        assertEquals(10000, balance); assertEquals(0, swords()); assertFalse(store.blocked(player.getUniqueId()));
        String quota = Files.readString(root.resolve("playerdata/" + player.getUniqueId() + ".yml"));
        assertFalse(quota.contains("pendingQuantity")); assertFalse(quota.contains("count: 1"));
        assertTrue(guard.execute(player.getUniqueId(), menu.getId(), button.getId(), () -> CompletableFuture.completedFuture(null)).join());
    }
    MenuDefinition menu() {
        return MenuJson.createMapper().readValue("""
            {"schemaVersion":1,"id":"shop","title":"Shop","dialogType":"multi_action",
             "inputs":[{"type":"number_range","key":"amount","label":"数量","start":1,"end":3,"step":1},{"type":"bool","key":"confirm","label":"确认"}],
             "actions":[{"id":"buy","label":"购买","conditions":{"operator":"AND","items":[
               {"type":"string","operator":"==","left":{"source":"input","key":"confirm"},"right":{"source":"literal","value":"true"}},
               {"type":"placeholder","placeholder":"%balance%","comparison":"numeric","operator":">=","value":"1000"}]},
               "actions":[{"type":"economy_withdraw","amount":"1000 * $(amount)"},{"type":"give_item","material":"DIAMOND_SWORD","amount":"$(amount)"}],
               "denyActions":[{"type":"message","text":"条件未满足"}],"limit":{"buy":{"player":3,"reset":"DAILY"}},"cooldown":{"buy":0}}]}
            """, MenuDefinition.class);
    }
    void click(MenuDefinition menu, int amount, boolean confirm) throws Exception {
        DialogResponseView response = new DialogResponseView() {
            public net.kyori.adventure.nbt.api.BinaryTagHolder payload() { throw new UnsupportedOperationException(); }
            public String getText(String key) { return null; }
            public Float getFloat(String key) { return key.equals("amount") ? (float) amount : null; }
            public Boolean getBoolean(String key) { return key.equals("confirm") ? confirm : null; }
        };
        await(engine.executeAsync(player, menu, menu.getActions().getFirst(), response));
    }
    <T> T await(CompletableFuture<T> future) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!future.isDone() && System.nanoTime() < deadline) { server.getScheduler().performTicks(1); Thread.sleep(3); }
        assertTrue(future.isDone(), "Future did not finish"); return future.join();
    }
    int swords() { return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull).filter(item -> item.getType() == Material.DIAMOND_SWORD).mapToInt(ItemStack::getAmount).sum(); }
    MenuDefinition woodShop() throws Exception {
        var menu = MenuJson.createMapper().readValue(Files.readString(Path.of("../examples/vanilla-shop/data/menus/vs_item_oak_log.json")), MenuDefinition.class);
        menu.getActions().get(0).getLimit().get("buy").setPlayer(3);
        menu.getActions().get(1).getLimit().get("buy").setPlayer(3);
        return menu;
    }
    void trade(MenuDefinition menu, int button, int buySlider, String buyText, int sellSlider, String sellText) throws Exception {
        var response = new DialogResponseView() {
            public net.kyori.adventure.nbt.api.BinaryTagHolder payload() { throw new UnsupportedOperationException(); }
            public String getText(String key) { return key.equals("buy_text") ? buyText : key.equals("sell_text") ? sellText : null; }
            public Float getFloat(String key) { return key.equals("buy_slider") ? (float) buySlider : key.equals("sell_slider") ? (float) sellSlider : null; }
            public Boolean getBoolean(String key) { return null; }
        };
        await(engine.executeAsync(player, menu, menu.getActions().get(button), response));
    }
    int logs() { return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull).filter(item -> item.getType() == Material.OAK_LOG).mapToInt(ItemStack::getAmount).sum(); }

    @Test void shopInputMethodsShareQuotaAndSalesDoNotResetPurchases() throws Exception {
        var menu = woodShop();
        trade(menu, 0, 2, "0", 0, "0"); assertEquals(9998, balance); assertEquals(2, logs());
        trade(menu, 1, 0, "2", 0, "0"); assertEquals(9998, balance); assertEquals(2, logs());
        trade(menu, 1, 0, "1", 0, "0"); assertEquals(9997, balance); assertEquals(3, logs());
        trade(menu, 2, 0, "0", 3, "0"); assertEquals(10000, balance); assertEquals(0, logs());
        trade(menu, 0, 1, "0", 0, "0"); assertEquals(10000, balance); assertEquals(0, logs());
        assertFalse(store.blocked(player.getUniqueId()));
    }
    @Test void insufficientFundsShowsClearMessageAndDoesNotConsumeSharedQuota() throws Exception {
        var menu = woodShop(); balance = 1;
        trade(menu, 0, 2, "0", 0, "0");
        assertEquals(1, balance); assertEquals(0, logs());
        var messages = new ArrayList<String>(); String message;
        while ((message = player.nextMessage()) != null) messages.add(message);
        assertEquals(List.of("[DCMenu] 余额不足，无法购买。"), messages);
        assertFalse(store.blocked(player.getUniqueId()));
        balance = 100;
        trade(menu, 1, 0, "3", 0, "0");
        assertEquals(97, balance); assertEquals(3, logs());
    }
    @Test void unrelatedFailureDoesNotClaimInsufficientFunds() throws Exception {
        var menu = woodShop(); depositFails = true;
        player.getInventory().setItem(0, new ItemStack(Material.OAK_LOG, 1));
        trade(menu, 2, 0, "0", 1, "0");
        assertEquals(10000, balance); assertEquals(1, logs());
        String message = player.nextMessage(); assertNotNull(message);
        assertFalse(message.contains("余额不足")); assertTrue(message.contains("操作未完成"));
    }

    @Test void staleSaleInventoryCannotPayOutAndDepositFailureRestoresItems() throws Exception {
        var menu = woodShop();
        player.getInventory().setItem(0, new ItemStack(Material.OAK_LOG, 2));
        trade(menu, 2, 0, "0", 3, "0"); assertEquals(10000, balance); assertEquals(2, logs());
        depositFails = true;
        trade(menu, 3, 0, "0", 0, "2"); assertEquals(10000, balance); assertEquals(2, logs());
        depositFails = false;
        trade(menu, 3, 0, "0", 0, "2"); assertEquals(10002, balance); assertEquals(0, logs());
        assertFalse(store.blocked(player.getUniqueId()));
    }

    @Test void invalidManualQuantitiesNeverSpendOrConsumeQuota() throws Exception {
        var menu = woodShop();
        for (String invalid : List.of("-1", "0.5", "2+3", "9999", "NaN", "", "Infinity")) {
            trade(menu, 1, 0, invalid, 0, "0");
            assertEquals(10000, balance, invalid); assertEquals(0, logs(), invalid);
            assertFalse(store.blocked(player.getUniqueId()), invalid);
        }
        trade(menu, 1, 0, "3", 0, "0"); assertEquals(9997, balance); assertEquals(3, logs());
    }
    @Test void configuredPluginCommandReachesTargetWithoutAllowList() throws Exception {
        server.getCommandMap().register("tests", new org.bukkit.command.Command("rtptest") {
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                assertTrue(Bukkit.isPrimaryThread()); assertSame(player, sender);
                sender.sendMessage("目标插件已收到命令"); return true;
            }
        });
        var menu = menu(); var button = menu.getActions().getFirst();
        button.setConditions(null); button.setLimit(null); button.setCooldown(null);
        var command = new ActionStepDefinition.Command(); command.setCommand("rtptest"); command.setExecutor("player");
        button.setActions(List.of(command)); click(menu, 1, true);
        assertEquals("目标插件已收到命令", player.nextMessage()); assertNull(player.nextMessage());
        assertFalse(player.isOp()); assertEquals(10000, balance); assertFalse(store.blocked(player.getUniqueId()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"absent", "null", "exception", "linkage", "unresolved", "number"})
    void conditionErrorBlocksBothChainsAndQuotaAndSurvivesRestart(String mode) throws Exception {
        resolverDelegate = new PlaceholderResolver() {
            public Optional<String> resolve(org.bukkit.entity.Player p, String text) { throw new AssertionError(); }
            public Result resolveResult(org.bukkit.entity.Player p, String text) {
                return switch (mode) {
                    case "absent" -> Result.failure("PLACEHOLDERAPI_UNAVAILABLE");
                    case "null" -> null;
                    case "exception" -> throw new IllegalArgumentException("private output");
                    case "linkage" -> throw new NoClassDefFoundError("private output");
                    case "unresolved" -> Result.ok("%balance%");
                    default -> Result.ok("not a number");
                };
            }
        };
        var menu = menu(); var button = menu.getActions().getFirst();
        var denyDebit = new ActionStepDefinition.EconomyWithdraw(); denyDebit.setAmount("1");
        button.setDenyActions(List.of(denyDebit));
        button.getConditions().setOperator("OR"); button.getConditions().setNegate(true);
        button.getConditions().setItems(List.of(button.getConditions().getItems().get(1), button.getConditions().getItems().get(0)));
        click(menu, 1, true);
        assertEquals(10000, balance); assertEquals(0, swords()); assertEquals(0, withdrawalCalls.get());
        assertFalse(Files.exists(root.resolve("playerdata/" + player.getUniqueId() + ".yml")));
        assertFalse(store.blocked(player.getUniqueId()));
        try (var files = Files.list(root.resolve("data/transactions"))) {
            var receipt = MenuJson.createMapper().readTree(Files.readString(files.findFirst().orElseThrow()));
            assertEquals("conditionError", receipt.path("status").asString());
            assertEquals("conditions.items[0].left", receipt.path("conditionError").path("path").asString());
            assertFalse(receipt.path("conditionError").path("code").asString().isEmpty());
            assertFalse(receipt.path("conditionError").has("message"));
        }
        assertTrue(player.nextMessage().contains("条件无法读取")); assertNull(player.nextMessage());
        await(engine.closeAsync());
        try (var restarted = new ProductionStore(root.resolve("data"))) {
            restarted.initialize().join(); assertFalse(restarted.blocked(player.getUniqueId()));
        }
    }

    @Test void failedConditionErrorReceiptKeepsDurableGateAcrossRestart() throws Exception {
        placeholderFails = true; conditionReceiptFails = true;
        assertThrows(CompletionException.class, () -> click(menu(), 1, true));
        assertEquals(10000, balance); assertEquals(0, swords()); assertEquals(0, withdrawalCalls.get());
        assertTrue(store.blocked(player.getUniqueId())); await(engine.closeAsync());
        try (var restarted = new ProductionStore(root.resolve("data"))) {
            restarted.initialize().join(); assertTrue(restarted.blocked(player.getUniqueId()));
        }
    }

    @Test void confirmationAndBrokenPlaceholderCannotSpend() throws Exception {
        click(menu(), 1, false); assertEquals(10000, balance); assertEquals(0, swords());
        placeholderFails = true; click(menu(), 1, true); assertEquals(10000, balance); assertEquals(0, swords()); assertFalse(store.blocked(player.getUniqueId()));
    }
    @Test void quantityCountsItemsNotClicksAndSurvivesServiceRestart() throws Exception {
        click(menu(), 2, true); assertEquals(8000, balance); assertEquals(2, swords());
        click(menu(), 2, true); assertEquals(8000, balance); assertEquals(2, swords());
        engine.close();
        // A new limit instance reads the committed counter rather than resetting it.
        try (var limits = new LimitService(root, scheduler)) {
            var key = "button:" + UUID.nameUUIDFromBytes("shop:buy:buy".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var result = await(limits.execute(player.getUniqueId(), new LimitService.Policy(key, 3, 2, LimitService.Reset.DAILY, 0, 0), () -> true, () -> true,
                    () -> CompletableFuture.completedFuture(LimitService.Outcome.COMMITTED)));
            assertEquals(LimitService.Status.LIMIT_REACHED, result.status());
        }
        assertFalse(store.blocked(player.getUniqueId()));
    }
    @Test void fullInventoryRefundsAndDoesNotConsumeQuota() throws Exception {
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        click(menu(), 1, true); assertEquals(10000, balance); assertFalse(store.blocked(player.getUniqueId()));
        assertTrue(player.nextMessage().contains("已回滚可撤销的数据修改"));
        player.getInventory().clear(); click(menu(), 3, true); assertEquals(7000, balance); assertEquals(3, swords());
    }
    @Test void failedRefundWritesRecoveryAndBlocksFurtherTransactionsAcrossRestart() throws Exception {
        refundFails = true; for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        click(menu(), 1, true); assertEquals(9000, balance); assertTrue(store.blocked(player.getUniqueId()));
        String message = player.nextMessage(); assertTrue(message.contains("待核查")); assertFalse(message.contains("已回滚"));
        click(menu(), 1, true); assertEquals(9000, balance);
        try (var files = Files.list(root.resolve("data/recovery"))) {
            var recoveries = files.filter(file -> file.toString().endsWith(".json")).toList(); assertEquals(1, recoveries.size());
            var recovery = MenuJson.createMapper().readTree(Files.readAllBytes(recoveries.getFirst()));
            var journal = root.resolve("data/transactions/" + recovery.path("transactionId").asString() + ".json");
            assertTrue(Files.exists(journal), "Recovery must point at the same durable transaction");
            assertEquals("recoveryRequired", MenuJson.createMapper().readTree(Files.readAllBytes(journal)).path("status").asString());
        }
        try (var restarted = new ProductionStore(root.resolve("data"))) { restarted.initialize().join(); assertTrue(restarted.blocked(player.getUniqueId())); }
    }
    @Test void playerDataAtomicMutationAndReverseCompensation() throws Exception {
        UUID id = player.getUniqueId(); UUID receipt = await(store.begin(id, MenuJson.createMapper().valueToTree(menu()), "buy", Map.of()));
        var first = await(store.set(id, "counter", MenuJson.createMapper().readTree("2")));
        var second = await(store.increment(id, "counter", 3)); assertEquals(5, store.find(id, "counter").orElseThrow().asDouble());
        await(second.rollback()); await(first.rollback()); assertTrue(store.find(id, "counter").isEmpty()); await(store.finish(id, receipt, "rolledBack"));
    }
    @Test void multipleRulesReserveTogetherAndCooldownPreventsSecondSpend() throws Exception {
        var menu = menu(); var button = menu.getActions().getFirst();
        var extra = new LimitDefinition(); extra.setPlayer(5); extra.setReset("NEVER"); button.setLimit(Map.of("buy", button.getLimit().get("buy"), "all", extra));
        button.setCooldown(Map.of("buy", 60d)); click(menu, 1, true); click(menu, 1, true);
        assertEquals(9000, balance); assertEquals(1, swords()); assertFalse(store.blocked(player.getUniqueId()));
    }
    @Test void invalidRuntimeQuantityDoesNotPermanentlyBlockAPlayer() throws Exception {
        var menu = menu();
        ((ActionStepDefinition.GiveItem) menu.getActions().getFirst().getActions().get(1)).setAmount("1 / ($(amount) - 1)");
        click(menu, 1, true);
        assertEquals(10000, balance); assertEquals(0, swords()); assertFalse(store.blocked(player.getUniqueId()));
        click(menu(), 1, true); assertEquals(9000, balance); assertEquals(1, swords());
    }
    @Test void cooldownWithoutQuotaAllowsRandomActions() throws Exception {
        var menu = menu(); var button = menu.getActions().getFirst(); button.setLimit(null); button.setCooldown(Map.of("buy", 60d));
        button.getActions().get(1).setChance(0d);
        click(menu, 1, true); click(menu, 1, true);
        assertEquals(9000, balance); assertEquals(0, swords()); assertFalse(store.blocked(player.getUniqueId()));
    }
    @Test void shutdownDuringFileIntentSettlesEvenAfterSchedulerRejectsAndPreservesRecovery() throws Exception {
        var menu = menu(); var button = menu.getActions().getFirst();
        menu.setInputs(null); button.setConditions(null); button.setLimit(null); button.setCooldown(null);
        var request = engine.executeAsync(player, menu, button, null);
        var stopped = engine.closeAsync(); scheduler.close();
        stopped.get(5, TimeUnit.SECONDS);
        assertTrue(request.isDone()); assertFalse(store.ready());
        assertEquals(10000, balance); assertEquals(0, swords());
        try (var restarted = new ProductionStore(root.resolve("data"))) {
            restarted.initialize().get(5, TimeUnit.SECONDS);
            assertTrue(restarted.blocked(player.getUniqueId()), "Interrupted intent must not be silently cleared");
        }
    }
    @Test void closeDuringInitializationCannotPublishReadyAfterClosure() throws Exception {
        var initializing = new ProductionStore(root.resolve("initializing"));
        var started = initializing.initialize(); initializing.close();
        started.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
        assertFalse(initializing.ready());
        assertTrue(initializing.initialize().isCompletedExceptionally());
    }
    @Test void quotaAndJournalAreDurableBeforeCommandAndSuccessMessage() throws Exception {
        var menu = menu(); var button = menu.getActions().getFirst();
        server.getCommandMap().register("tests", new org.bukkit.command.Command("persisttest") {
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                assertTrue(Bukkit.isPrimaryThread());
                try {
                    String yaml = Files.readString(root.resolve("playerdata/" + player.getUniqueId() + ".yml"));
                    assertTrue(yaml.contains("count: 1")); assertFalse(yaml.contains("pendingQuantity"));
                    try (var paths = Files.list(root.resolve("data/transactions"))) {
                        var journal = MenuJson.createMapper().readTree(Files.readAllBytes(paths.findFirst().orElseThrow()));
                        assertEquals("effectsPending", journal.path("status").asString());
                    }
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                sender.sendMessage("状态已落盘"); return true;
            }
        });
        var command = new ActionStepDefinition.Command(); command.setCommand("persisttest");
        var message = new ActionStepDefinition.Message(); message.setText(TextSpec.fromJson(MenuJson.createMapper().getNodeFactory().stringNode("购买成功")));
        var chain = new ArrayList<>(button.getActions()); chain.add(command); chain.add(message); button.setActions(chain);
        click(menu, 1, true);
        assertEquals("状态已落盘", player.nextMessage()); assertEquals("购买成功", player.nextMessage());
        assertFalse(store.blocked(player.getUniqueId()));
    }
    @Test void failedDurableCheckpointDoesNotSendSuccessOrDispatchCommandAndBlocksRestart() throws Exception {
        var menu = menu(); var button = menu.getActions().getFirst(); checkpointWriteFails = true;
        var message = new ActionStepDefinition.Message(); message.setText(TextSpec.fromJson(MenuJson.createMapper().getNodeFactory().stringNode("不应发送成功")));
        var command = new ActionStepDefinition.Command(); command.setCommand("notexecuted");
        server.getCommandMap().register("tests", new org.bukkit.command.Command("notexecuted") {
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) { fail("must not dispatch"); return true; }
        });
        var chain = new ArrayList<>(button.getActions()); chain.add(message); chain.add(command); button.setActions(chain);
        click(menu, 1, true);
        assertEquals(9000, balance); assertEquals(1, swords()); assertTrue(store.blocked(player.getUniqueId()));
        String feedback; while ((feedback = player.nextMessage()) != null) assertFalse(feedback.contains("不应发送成功"));
        try (var restarted = new ProductionStore(root.resolve("data"))) { restarted.initialize().join(); assertTrue(restarted.blocked(player.getUniqueId())); }
    }
    @Test void finalReceiptFailurePreservesEffectsPendingEvidenceAndDoesNotAllowRetry() throws Exception {
        var menu = menu(); finalReceiptFails = true;
        assertThrows(CompletionException.class, () -> click(menu, 1, true));
        assertEquals(9000, balance); assertEquals(1, swords()); assertTrue(store.blocked(player.getUniqueId()));
        try (var paths = Files.list(root.resolve("data/transactions"))) {
            assertEquals("effectsPending", MenuJson.createMapper().readTree(Files.readAllBytes(paths.findFirst().orElseThrow())).path("status").asString());
        }
        click(menu, 1, true); assertEquals(9000, balance); assertEquals(1, swords());
        try (var restarted = new ProductionStore(root.resolve("data"))) { restarted.initialize().join(); assertTrue(restarted.blocked(player.getUniqueId())); }
    }
    @Test void failedRecoveryRecordStillLeavesDurableBlockedIntentAcrossRestart() throws Exception {
        refundFails = true; recoveryWriteFails = true;
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        click(menu(), 1, true); assertEquals(9000, balance); assertTrue(store.blocked(player.getUniqueId()));
        try (var paths = Files.list(root.resolve("data/recovery"))) { assertEquals(0, paths.count()); }
        try (var paths = Files.list(root.resolve("data/transactions"))) {
            assertEquals("recoveryRequired", MenuJson.createMapper().readTree(Files.readAllBytes(paths.findFirst().orElseThrow())).path("status").asString());
        }
        try (var restarted = new ProductionStore(root.resolve("data"))) { restarted.initialize().join(); assertTrue(restarted.blocked(player.getUniqueId())); }
    }
}
