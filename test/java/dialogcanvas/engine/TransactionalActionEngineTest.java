package dialogcanvas.engine;

import dialogcanvas.engine.action.*;
import dialogcanvas.engine.condition.ConditionResult;
import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static dialogcanvas.engine.action.ActionChainResult.Status.*;

class TransactionalActionEngineTest {
    ServerMock server; Scheduler scheduler; RecordingPlayer player;
    TransactionalActionEngine engine; Memory memory;
    final JsonMapper mapper = MenuJson.createMapper();
    MenuDefinition menu;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); scheduler = new Scheduler(MockBukkit.createMockPlugin());
        player = new RecordingPlayer(server, "Buyer"); server.addPlayer(player);
        memory = new Memory(); menu = new MenuDefinition(); menu.setId("shop");
        engine = new TransactionalActionEngine(scheduler, memory.services(), Logger.getAnonymousLogger(), () -> .5);
    }
    @AfterEach void teardown() throws Exception { await(engine.shutdown()); scheduler.close(); MockBukkit.unmock(); }
    List<ActionStepDefinition> steps(String json) {
        return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, ActionStepDefinition.class));
    }
    CompletableFuture<ActionChainResult> run(String json) { return engine.executeChain(player, menu, steps(json), Map.of()); }
    <T> T await(CompletableFuture<T> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && System.nanoTime() < deadline) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        return future.get(1, TimeUnit.SECONDS);
    }
    @Test void plainDeliveryDoesNotInheritLegacyCosmeticGlint() throws Exception {
        var result = await(run("""
            [{"type":"give_item","material":"DIAMOND_SWORD","amount":"1","glow":true}]
            """));
        assertEquals(COMMITTED, result.status());
        var delivered = player.getInventory().getItem(0);
        assertEquals(Material.DIAMOND_SWORD, delivered.getType());
        assertTrue(delivered.getEnchantments().isEmpty());
        assertFalse(delivered.getItemMeta().hasEnchantmentGlintOverride());
    }
    @Test void enchantedDeliveryAndExactRemovalDoNotConsumeAnOrdinarySword() throws Exception {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        var result = await(run("""
            [{"type":"give_item","material":"minecraft:diamond_sword","amount":"1","name":"超级钻石剑","enchantments":{"sharpness":10},"unbreakable":true},
             {"type":"take_item","material":"diamond_sword","amount":"1","name":"超级钻石剑","enchantments":{"sharpness":10},"unbreakable":true}]
            """));
        assertEquals(COMMITTED, result.status());
        assertEquals(Material.DIAMOND_SWORD, player.getInventory().getItem(0).getType());
        assertTrue(player.getInventory().getItem(0).getEnchantments().isEmpty());
        assertNull(player.getInventory().getItem(1));
    }

    @Test void customizedDeliveryRollsBackWhenLaterStateActionFails() throws Exception {
        memory.incrementFails = true;
        assertEquals(ROLLED_BACK, await(run("""
            [{"type":"give_item","material":"diamond_sword","amount":"1","enchantments":{"sharpness":10}},
             {"type":"player_data_increment","key":"fail","amount":"1"}]
            """ )).status());
        assertNull(player.getInventory().getItem(0));
    }
    @Test void reversibleActionsCommitBeforeOrderedEffects() throws Exception {
        var result = await(run("""
            [{"type":"message","text":"done"},
             {"type":"economy_withdraw","amount":"100"},
             {"type":"economy_deposit","amount":"20"},
             {"type":"player_data_set","key":"count","value":4},
             {"type":"player_data_increment","key":"count","amount":"2"},
             {"type":"give_item","material":"DIAMOND","amount":"3"},
             {"type":"take_item","material":"DIAMOND","amount":"1"},
             {"type":"play_sound","sound":"BLOCK_NOTE_BLOCK_PLING"},
             {"type":"open_menu","menuId":"other"},
             {"type":"close_menu"},{"type":"refresh_menu"}]
            """));
        assertEquals(COMMITTED, result.status()); assertEquals(920, memory.balance);
        assertEquals(6, memory.data.get("count").doubleValue());
        assertEquals(2, player.getInventory().getItem(0).getAmount());
        assertEquals(List.of("withdraw", "deposit", "set:count", "increment:count", "message", "sound:minecraft:block.note_block.pling", "open:other", "close", "refresh"), memory.events);
    }
    @Test void failureRollsBackInReverseOrderAndRestoresAbsentKeys() throws Exception {
        memory.incrementFails = true;
        var result = await(run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"player_data_set","key":"count","value":1},
             {"type":"give_item","material":"DIAMOND","amount":"3"},
             {"type":"message","text":"must not send"},
             {"type":"player_data_increment","key":"count","amount":"1"}]
            """));
        assertEquals(ROLLED_BACK, result.status()); assertEquals(1000, memory.balance);
        assertFalse(memory.data.containsKey("count")); assertNull(player.getInventory().getItem(0));
        assertEquals(List.of("withdraw", "set:count", "increment:count", "undo:set:count", "undo:withdraw"), memory.events);
    }
    @Test void withdrawalFailureNeverDelivers() throws Exception {
        memory.withdrawFails = true;
        assertEquals(ROLLED_BACK, await(run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"give_item","material":"DIAMOND","amount":"1"}]
            """)).status());
        assertNull(player.getInventory().getItem(0)); assertEquals(1000, memory.balance);
    }
    @Test void depositIncrementAndTakeCompensateExactPreviousState() throws Exception {
        memory.withdrawFails = true; memory.data.put("count", mapper.readTree("7"));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        assertEquals(ROLLED_BACK, await(run("""
            [{"type":"economy_deposit","amount":"20"},
             {"type":"player_data_set","key":"count","value":4},
             {"type":"player_data_increment","key":"count","amount":"2"},
             {"type":"take_item","material":"DIAMOND","amount":"2"},
             {"type":"give_item","material":"DIAMOND","amount":"3"},
             {"type":"economy_withdraw","amount":"100"}]
            """)).status());
        assertEquals(1000, memory.balance); assertEquals(7, memory.data.get("count").intValue());
        assertEquals(5, player.getInventory().getItem(0).getAmount());
        assertEquals(List.of("deposit", "set:count", "increment:count", "withdraw",
                "undo:increment:count", "undo:set:count", "undo:deposit"), memory.events);
    }
    @Test void inventoryCapacityFailureRefunds() throws Exception {
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        assertEquals(ROLLED_BACK, await(run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"give_item","material":"DIAMOND","amount":"1"}]
            """)).status());
        assertEquals(1000, memory.balance); assertEquals(Material.STONE, player.getInventory().getItem(0).getType());
    }
    @Test void takingTooManyDoesNotPartiallyRemoveItems() throws Exception {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
        assertEquals(ROLLED_BACK, await(run("[{\"type\":\"take_item\",\"material\":\"DIAMOND\",\"amount\":\"3\"}]")).status());
        assertEquals(2, player.getInventory().getItem(0).getAmount());
    }
    @Test void compensationFailureContinuesAndPersistsRecovery() throws Exception {
        memory.incrementFails = true; memory.dataUndoFails = true;
        assertEquals(RECOVERY_REQUIRED, await(run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"player_data_set","key":"count","value":1},
             {"type":"player_data_increment","key":"count","amount":"1"}]
            """)).status());
        assertEquals(1000, memory.balance); assertEquals(1, memory.recoveries.size());
        assertEquals("player_data_set", memory.recoveries.getFirst().failures().getFirst().actionType());
    }
    @Test void recoverySaveFailureReportedSeparately() throws Exception {
        memory.incrementFails = true; memory.dataUndoFails = true; memory.recoveryFails = true;
        assertEquals(RECOVERY_SAVE_FAILED, await(run("""
            [{"type":"player_data_set","key":"count","value":1},
             {"type":"player_data_increment","key":"count","amount":"1"}]
            """)).status());
    }
    @Test void inventoryRollbackDoesNotOverwriteExternalChanges() throws Exception {
        CompletableFuture<ActionServices.Change> pause = new CompletableFuture<>(); memory.nextWithdrawal = pause;
        var result = run("""
            [{"type":"give_item","material":"DIAMOND","amount":"1"},
             {"type":"economy_withdraw","amount":"100"}]
            """);
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD));
        pause.completeExceptionally(new IllegalStateException("withdraw failed"));
        assertEquals(RECOVERY_REQUIRED, await(result).status());
        assertEquals(Material.EMERALD, player.getInventory().getItem(0).getType());
    }
    @Test void delayWaitsAndReturnCommitsPrefix() throws Exception {
        var future = run("""
            [{"type":"economy_withdraw","amount":"100","delay":3},
             {"type":"message","text":"prefix"},
             {"type":"return","delay":2},
             {"type":"economy_deposit","amount":"999"}]
            """);
        assertEquals(1000, memory.balance); assertFalse(future.isDone());
        server.getScheduler().performTicks(3); assertEquals(900, memory.balance); assertFalse(future.isDone());
        assertEquals(COMMITTED, await(future).status()); assertEquals(900, memory.balance);
        assertEquals(List.of("withdraw", "message"), memory.events);
    }
    @Test void chanceSkipsActionsAndReturn() throws Exception {
        assertEquals(COMMITTED, await(run("""
            [{"type":"economy_withdraw","amount":"100","chance":0},
             {"type":"return","chance":0},
             {"type":"economy_deposit","amount":"20","chance":0.4},
             {"type":"economy_deposit","amount":"10","chance":1}]
            """)).status()); assertEquals(1010, memory.balance);
    }
    @Test void samePlayerQueuesTransactions() throws Exception {
        var first = run("[{\"type\":\"economy_withdraw\",\"amount\":\"100\",\"delay\":3}]");
        var second = run("[{\"type\":\"economy_deposit\",\"amount\":\"20\"}]");
        assertEquals(1000, memory.balance); assertFalse(second.isDone());
        await(first); await(second); assertEquals(List.of("withdraw", "deposit"), memory.events);
    }
    @Test void workerEntryAndAsyncProviderResumeOnMainThread() throws Exception {
        CompletableFuture<ActionServices.Change> pause = new CompletableFuture<>(); memory.nextWithdrawal = pause;
        var submitted = CompletableFuture.supplyAsync(() -> run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"player_data_set","key":"thread","value":true},
             {"type":"message","text":"done"}]
            """));
        var result = await(submitted);
        while (!memory.events.contains("withdraw")) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        CompletableFuture.runAsync(() -> pause.complete(memory.receipt("withdraw", () -> {}))).get();
        assertEquals(COMMITTED, await(result).status());
        assertEquals(List.of("withdraw", "set:thread", "message"), memory.events);
    }
    @Test void shutdownCancelsDelayAndRollsBackBeforeSchedulerCloses() throws Exception {
        var result = run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"player_data_set","key":"later","value":1,"delay":100}]
            """);
        await(engine.shutdown()); assertEquals(ROLLED_BACK, await(result).status()); assertEquals(1000, memory.balance);
        assertEquals(REJECTED, await(run("[]")).status());
    }
    @Test void shutdownWaitsForInFlightReceiptThenCompensates() throws Exception {
        CompletableFuture<ActionServices.Change> pause = new CompletableFuture<>(); memory.nextWithdrawal = pause;
        var result = run("[{\"type\":\"economy_withdraw\",\"amount\":\"100\"}]");
        var stopping = engine.shutdown(); assertFalse(stopping.isDone());
        pause.complete(memory.receipt("withdraw", () -> memory.events.add("recovered")));
        await(stopping); assertEquals(ROLLED_BACK, await(result).status()); assertTrue(memory.events.contains("recovered"));
    }
    @Test void lifecycleCutoffSettlesInFlightAndQueuedWorkAndIgnoresLateReceipt() throws Exception {
        CompletableFuture<ActionServices.Change> pause = new CompletableFuture<>(); memory.nextWithdrawal = pause;
        var first = run("[{\"type\":\"economy_withdraw\",\"amount\":\"100\"}]");
        var queued = run("[{\"type\":\"message\",\"text\":\"must not send\"}]");
        var stopping = engine.shutdown(); assertFalse(stopping.isDone());
        engine.abandonPending();
        assertEquals(RECOVERY_REQUIRED, await(first).status());
        assertEquals(RECOVERY_REQUIRED, await(queued).status());
        assertTrue(stopping.isDone());
        CompletableFuture.runAsync(() -> pause.complete(memory.receipt("withdraw", () -> fail("Late rollback must not execute")))).get();
        server.getScheduler().performTicks(2);
        assertEquals(List.of("withdraw"), memory.events);
    }
    @Test void disconnectDuringDelayDoesNotClaimOfflineInventoryWasRestored() throws Exception {
        var result = run("""
            [{"type":"give_item","material":"DIAMOND","amount":"1"},
             {"type":"economy_withdraw","amount":"100","delay":3}]
            """);
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        player.simulateOffline = true;
        assertEquals(RECOVERY_REQUIRED, await(result).status());
        assertEquals(1000, memory.balance);
        assertEquals(1, memory.recoveries.size());
        assertEquals("give_item", memory.recoveries.getFirst().failures().getFirst().actionType());
    }
    @Test void postCommitFailureDoesNotClaimRollback() throws Exception {
        memory.menuFails = true;
        assertEquals(POST_COMMIT_FAILED, await(run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"open_menu","menuId":"missing"},
             {"type":"message","text":"must not send"}]
            """)).status());
        assertEquals(900, memory.balance); assertEquals(List.of("withdraw", "open:missing"), memory.events);
    }
    @Test void literalCommandAndSeparateValidatedArguments() throws Exception {
        server.getCommandMap().register("tests", new Command("safe") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main(); assertSame(player, sender); assertArrayEquals(new String[] {"Buyer", "2"}, args);
                memory.events.add("command"); return true;
            }
        });
        var inputs = Map.of("name", mapper.readTree("\"Buyer\""), "amount", mapper.readTree("2"));
        assertEquals(COMMITTED, await(engine.executeChain(player, menu, steps("""
            [{"type":"command","command":"safe","arguments":["$(name)","$(amount)"]}]
            """), inputs)).status()); assertEquals(List.of("command"), memory.events);
    }
    @ParameterizedTest @ValueSource(strings = {"Buyer op", "Buyer;op", "Buyer\nop", "\"Buyer\"", "$(other)", "@a", "~", "{player}"})
    void commandInputInjectionRejectedBeforeStateChanges(String value) throws Exception {
        Map<String, JsonNode> inputs = Map.of("name", mapper.valueToTree(value));
        var result = await(engine.executeChain(player, menu, steps("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"command","command":"safe","arguments":["$(name)"]}]
            """), inputs));
        assertEquals(ROLLED_BACK, result.status()); assertEquals(1000, memory.balance); assertTrue(memory.events.isEmpty());
    }
    @Test void consoleCommandRunsWithoutExtraPermissionGate() throws Exception {
        server.getCommandMap().register("tests", new Command("safe") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main(); assertSame(Bukkit.getConsoleSender(), sender); return true;
            }
        });
        assertEquals(COMMITTED, await(run("[{\"type\":\"command\",\"command\":\"safe\",\"executor\":\"console\"}]")).status());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void opCommandUsesPlayerAndRestoresOriginalPrivilege(boolean originallyOp) throws Exception {
        player.setOp(originallyOp);
        server.getCommandMap().register("tests", new Command("safe") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main(); assertSame(player, sender); assertTrue(sender.isOp()); return true;
            }
        });
        var result = run("[{\"type\":\"command\",\"command\":\"safe\",\"executor\":\"op\",\"delay\":3}]");
        assertEquals(originallyOp, player.isOp(), "Waiting must not elevate permissions");
        assertEquals(COMMITTED, await(result).status()); assertEquals(originallyOp, player.isOp());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void throwingOpCommandAlwaysRestoresPrivilege(boolean originallyOp) throws Exception {
        player.setOp(originallyOp);
        server.getCommandMap().register("tests", new Command("safe") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main(); assertTrue(sender.isOp()); throw new IllegalStateException("test command failure");
            }
        });
        assertEquals(POST_COMMIT_FAILED, await(run("[{\"type\":\"command\",\"command\":\"safe\",\"executor\":\"op\"}]")).status());
        assertEquals(originallyOp, player.isOp());
    }
    @Test void invalidExecutorNeverElevates() throws Exception {
        assertFalse(player.isOp());
        assertEquals(ROLLED_BACK, await(run("[{\"type\":\"command\",\"command\":\"safe\",\"executor\":\"unknown\"}]")).status());
        assertFalse(player.isOp());
    }
    @Test void failedCommandDiagnosticsNeverLogArgumentsOrThirdPartyExceptionText() throws Exception {
        var records = new java.util.concurrent.ConcurrentLinkedQueue<java.util.logging.LogRecord>();
        var callbackLogged = new CompletableFuture<Void>();
        var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { records.add(record); if (record.getMessage().startsWith("Action callback failed")) callbackLogged.complete(null); }
            public void flush() {} public void close() {}
        });
        engine = new TransactionalActionEngine(scheduler, memory.services(), logger, () -> .5);
        String privateArgument = "fixture-private-command-argument";
        server.getCommandMap().register("tests", new Command("privatecmd") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main(); assertTrue(sender.isOp()); assertEquals(privateArgument, args[0]);
                throw new org.bukkit.command.CommandException("Third party rejected: " + privateArgument);
            }
        });
        var result = await(run("[{\"type\":\"command\",\"command\":\"privatecmd\",\"executor\":\"op\",\"arguments\":[\"" + privateArgument + "\"]}]"));
        assertEquals(POST_COMMIT_FAILED, result.status()); assertFalse(player.isOp());
        assertFalse(records.isEmpty());
        assertTrue(records.stream().noneMatch(record -> record.getMessage().contains(privateArgument) || record.getThrown() != null), "Diagnostic log must exclude command arguments and exception payload");
        assertTrue(records.stream().anyMatch(record -> record.getMessage().contains(result.transactionId().toString()) && record.getMessage().contains("POST_COMMIT_FAILED")));
        records.clear();
        var callback = new ActionDefinition(); callback.setId("private_callback"); callback.setActionType("custom");
        callback.setActions(steps("[{\"type\":\"command\",\"command\":\"privatecmd\",\"executor\":\"op\",\"arguments\":[\"" + privateArgument + "\"]}]"));
        engine.execute(player, menu, callback, null); await(callbackLogged);
        assertFalse(player.isOp());
        assertTrue(records.stream().noneMatch(record -> record.getMessage().contains(privateArgument) || record.getThrown() != null));
    }
    @Test void literalSelectorsUnicodeJsonAndPlayerReferencesArePreserved() throws Exception {
        server.getCommandMap().register("tests", new Command("safe") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main();
                assertEquals("@a[tag=vip]", args[0]); assertEquals("~", args[1]);
                assertEquals("Buyer", args[2]); assertEquals(player.getUniqueId().toString(), args[3]);
                assertEquals("{\"text\":\"中文 内容\"}", String.join(" ", Arrays.copyOfRange(args, 4, args.length)));
                return true;
            }
        });
        var command = new dialogcanvas.model.ActionStepDefinition.Command(); command.setCommand("safe");
        command.setArguments(List.of("@a[tag=vip]", "~", "{player}", "{uuid}", "{\"text\":\"中文 内容\"}"));
        assertEquals(COMMITTED, await(engine.executeChain(player, menu, List.of(command), Map.of())).status());
    }
    @Test void literalNewlineIsRejectedBeforeStateChanges() throws Exception {
        var command = new dialogcanvas.model.ActionStepDefinition.Command(); command.setCommand("safe"); command.setArguments(List.of("hello\nstop"));
        assertEquals(ROLLED_BACK, await(engine.executeChain(player, menu, List.of(command), Map.of())).status());
    }
    @Test void delayedPlayerCommandUsesTargetPermissionsAtExecutionTime() throws Exception {
        var plugin = MockBukkit.createMockPlugin();
        var attachment = player.addAttachment(plugin, "tests.rtp", true);
        server.getCommandMap().register("tests", new Command("safe") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main(); assertSame(player, sender);
                memory.events.add(sender.hasPermission("tests.rtp") ? "permitted" : "denied-by-target"); return true;
            }
        });
        var result = run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"command","command":"safe","executor":"player","delay":3}]
            """);
        player.removeAttachment(attachment);
        assertEquals(COMMITTED, await(result).status());
        assertEquals(900, memory.balance);
        assertEquals(List.of("withdraw", "denied-by-target"), memory.events);
    }
    @ParameterizedTest @ValueSource(strings = {"player", "op", "console"})
    void pluginChecksItsOwnReloadPermission(String executor) throws Exception {
        server.getPluginManager().addPermission(new org.bukkit.permissions.Permission("tests.rtp.reload", org.bukkit.permissions.PermissionDefault.OP));
        server.getCommandMap().register("tests", new Command("rtpreload") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main();
                memory.events.add(sender.hasPermission("tests.rtp.reload") ? "reloaded" : "denied-by-target");
                return true;
            }
        });
        var command = new ActionStepDefinition.Command(); command.setCommand("rtpreload"); command.setExecutor(executor);
        assertEquals(COMMITTED, await(engine.executeChain(player, menu, List.of(command), Map.of())).status());
        assertEquals(List.of("player".equals(executor) ? "denied-by-target" : "reloaded"), memory.events);
        assertFalse(player.isOp());
    }
    @Test void playerMayExecutePublicPluginCommandWithoutExtraGate() throws Exception {
        server.getPluginManager().addPermission(new org.bukkit.permissions.Permission("tests.rtp", org.bukkit.permissions.PermissionDefault.TRUE));
        server.getCommandMap().register("tests", new Command("rtp") {
            public boolean execute(CommandSender sender, String label, String[] args) {
                main(); assertSame(player, sender); assertFalse(sender.isOp());
                assertTrue(sender.hasPermission("tests.rtp")); memory.events.add("rtp"); return true;
            }
        });
        assertEquals(COMMITTED, await(run("[{\"type\":\"command\",\"command\":\"rtp\"}]")).status());
        assertEquals(List.of("rtp"), memory.events);
    }
    @Test void asynchronousRollbackWaitsThenContinuesOnMainThread() throws Exception {
        memory.incrementFails = true; memory.pendingUndo = new CompletableFuture<>();
        var result = run("""
            [{"type":"economy_withdraw","amount":"100"},
             {"type":"player_data_set","key":"count","value":1},
             {"type":"player_data_increment","key":"count","amount":"1"}]
            """);
        assertFalse(result.isDone()); assertEquals(900, memory.balance);
        assertFalse(memory.events.contains("undo:withdraw"));
        CompletableFuture.runAsync(() -> memory.pendingUndo.complete(null)).get();
        assertEquals(ROLLED_BACK, await(result).status()); assertEquals(1000, memory.balance);
        assertFalse(memory.data.containsKey("count"));
    }
    @Test void successfulMutationWithoutReceiptRequiresRecovery() throws Exception {
        memory.missingReceipt = true;
        assertEquals(RECOVERY_REQUIRED, await(run("[{\"type\":\"economy_deposit\",\"amount\":\"20\"}]")).status());
        assertEquals(1020, memory.balance); assertEquals(1, memory.recoveries.size());
    }
    @Test void callerCancellationDoesNotReleasePlayerQueue() throws Exception {
        CompletableFuture<ActionServices.Change> pending = new CompletableFuture<>(); memory.nextWithdrawal = pending;
        var first = run("[{\"type\":\"economy_withdraw\",\"amount\":\"100\"}]");
        var next = run("[{\"type\":\"economy_deposit\",\"amount\":\"20\"}]");
        first.cancel(false); assertFalse(next.isDone());
        pending.complete(memory.receipt("withdraw", () -> {}));
        assertEquals(COMMITTED, await(next).status());
        assertEquals(List.of("withdraw", "deposit"), memory.events);
    }
    @Test void outerClickCancellationDoesNotReleaseGuardOrUnderlyingPlayerQueue() throws Exception {
        var pending = new CompletableFuture<ActionServices.Change>(); memory.nextWithdrawal = pending;
        var guard = new ClickGuard();
        var first = guard.execute(player.getUniqueId(), "shop", "buy",
                () -> run("[{\"type\":\"economy_withdraw\",\"amount\":\"100\"}]"));
        first.cancel(false);
        assertFalse(guard.execute(player.getUniqueId(), "shop", "buy", () -> { fail(); return null; }).join());
        var next = run("[{\"type\":\"economy_deposit\",\"amount\":\"20\"}]");
        assertFalse(next.isDone()); pending.complete(memory.receipt("withdraw", () -> {}));
        assertEquals(COMMITTED, await(next).status());
        assertTrue(guard.execute(player.getUniqueId(), "shop", "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
    @Test void thrownCommandIsPostCommitFailure() throws Exception {
        server.getCommandMap().register("tests", new Command("safe") {
            public boolean execute(CommandSender sender, String label, String[] args) { throw new IllegalStateException("failed command"); }
        });
        assertEquals(POST_COMMIT_FAILED, await(run("""
            [{"type":"economy_withdraw","amount":"100"},{"type":"command","command":"safe"}]
            """)).status()); assertEquals(900, memory.balance);
    }
    @Test void conditionFailureRunsDenyActionsAndErrorFailsClosed() throws Exception {
        ActionDefinition button = new ActionDefinition();
        ConditionDefinition condition = new ConditionDefinition(); condition.setType("permission"); condition.setPermission("shop.buy");
        button.setConditions(condition);
        button.setActions(steps("[{\"type\":\"economy_deposit\",\"amount\":\"100\"}]"));
        button.setDenyActions(steps("[{\"type\":\"message\",\"text\":\"denied\"}]"));
        memory.conditionStatus = ConditionResult.Status.FAIL;
        await(engine.executeAsync(player, menu, button, null));
        assertEquals(1000, memory.balance); assertEquals(List.of("message"), memory.events);
        memory.events.clear(); memory.conditionStatus = ConditionResult.Status.ERROR;
        assertThrows(ExecutionException.class, () -> await(engine.executeAsync(player, menu, button, null)));
        assertTrue(memory.events.isEmpty());
    }
    @Test void unrelatedInventorySlotChangesSurviveRollback() throws Exception {
        CompletableFuture<ActionServices.Change> pause = new CompletableFuture<>(); memory.nextWithdrawal = pause;
        var result = run("""
            [{"type":"give_item","material":"DIAMOND","amount":"1"},
             {"type":"economy_withdraw","amount":"100"}]
            """);
        player.getInventory().setItem(10, new ItemStack(Material.EMERALD));
        pause.completeExceptionally(new IllegalStateException("failed"));
        assertEquals(ROLLED_BACK, await(result).status()); assertNull(player.getInventory().getItem(0));
        assertEquals(Material.EMERALD, player.getInventory().getItem(10).getType());
    }
    @Test void expressionUsesTypedInputAndInvalidExpressionPreventsAllMutations() throws Exception {
        assertEquals(COMMITTED, await(engine.executeChain(player, menu,
                steps("[{\"type\":\"economy_withdraw\",\"amount\":\"100 * $(amount)\"}]"), Map.of("amount", mapper.readTree("2")))).status());
        assertEquals(800, memory.balance);
        assertEquals(ROLLED_BACK, await(engine.executeChain(player, menu,
                steps("[{\"type\":\"economy_withdraw\",\"amount\":\"$(amount)\"}]"), Map.of("amount", mapper.readTree("\"1 + 100\"")))).status());
        assertEquals(800, memory.balance);
    }
    @Test void saleMessageShowsActualAmountAndCredit() throws Exception {
        player.getInventory().setItem(0, new ItemStack(Material.OAK_LOG, 64));
        player.getInventory().setItem(1, new ItemStack(Material.OAK_LOG, 64));
        var result = await(engine.executeChain(player, menu, steps("""
            [{"type":"take_item","material":"OAK_LOG","amount":"$(sell_slider)"},
             {"type":"economy_deposit","amount":"1 * $(sell_slider)"},
             {"type":"message","text":"&6出售橡木原木 ×$(sell_slider)，获得 $(1 * $(sell_slider)) 游戏币。"}]
            """), Map.of("sell_slider", mapper.valueToTree(128))));
        assertEquals(COMMITTED, result.status()); assertEquals(1128, memory.balance);
        assertEquals("出售橡木原木 ×128，获得 128 游戏币。", memory.messages.getFirst());
    }
    @Test void purchaseMessageShowsActualCostAndDeliveredQuantity() throws Exception {
        var result = await(engine.executeChain(player, menu, steps("""
            [{"type":"economy_withdraw","amount":"1 * $(buy_text)"},
             {"type":"give_item","material":"OAK_LOG","amount":"$(buy_text)"},
             {"type":"message","text":"&a花费 $(1 * $(buy_text)) 游戏币，购买橡木原木 ×$(buy_text)。"}]
            """), Map.of("buy_text", mapper.valueToTree(128))));
        assertEquals(COMMITTED, result.status()); assertEquals(872, memory.balance);
        assertEquals("花费 128 游戏币，购买橡木原木 ×128。", memory.messages.getFirst());
    }
    @Test void invalidMessageArithmeticFailsBeforeDebit() throws Exception {
        var result = await(engine.executeChain(player, menu, steps("""
            [{"type":"economy_withdraw","amount":"1"},
             {"type":"message","text":"金额 $(1 / $(zero))"}]
            """), Map.of("zero", mapper.valueToTree(0))));
        assertEquals(ROLLED_BACK, result.status()); assertEquals(1000, memory.balance);
        assertTrue(memory.events.isEmpty());
    }
    @Test void papiMessageUsesCommittedBalanceOnMainThread() throws Exception {
        engine = new TransactionalActionEngine(scheduler, memory.services(), Logger.getAnonymousLogger(), () -> .5,
                (p, token) -> { main(); assertEquals("%vault_eco_balance%", token); return Optional.of("" + (int) memory.balance); });
        assertEquals(COMMITTED, await(run("""
            [{"type":"economy_withdraw","amount":"128"},
             {"type":"message","text":"余额 %vault_eco_balance%"}]
            """)).status());
        assertEquals("余额 872", memory.messages.getFirst());
    }
    @ParameterizedTest @ValueSource(strings = {"null", "unchecked", "exception", "linkage", "secret"})
    void checkedMessageBoundaryPreservesCommitAndDoesNotLeakProviderData(String mode) throws Exception {
        var logs = new ArrayList<String>();
        var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            public void flush() {} public void close() {}
        });
        var resolver = new dialogcanvas.engine.condition.PlaceholderResolver() {
            public Optional<String> resolve(org.bukkit.entity.Player p, String token) { throw new AssertionError(); }
            public Result resolveResult(org.bukkit.entity.Player p, String token) {
                return switch (mode) {
                    case "null" -> null;
                    case "unchecked" -> Result.ok("private_output%private_token%");
                    case "exception" -> throw new IllegalStateException("private_output");
                    case "linkage" -> throw new NoClassDefFoundError("private_output");
                    default -> Result.failure("private_output\nprivate_input");
                };
            }
        };
        engine = new TransactionalActionEngine(scheduler, memory.services(), logger, () -> .5, resolver);
        var result = await(run("""
            [{"type":"economy_withdraw","amount":"128"},{"type":"message","text":"%private_input%"}]
            """));
        assertEquals(POST_COMMIT_FAILED, result.status()); assertTrue(result.stateCommitted());
        assertEquals(872, memory.balance); assertTrue(memory.messages.isEmpty());
        assertFalse(result.message().contains("private_"));
        assertEquals(1, logs.stream().filter(line -> line.startsWith("Action message placeholder resolution failed")).count());
        assertTrue(logs.stream().anyMatch(line -> line.contains("actions[1].text")));
        assertTrue(logs.stream().noneMatch(line -> line.contains("private_")));
    }
    @Test void ordinaryDisplayTextWithLiteralPercentDoesNotCallPapi() throws Exception {
        engine = new TransactionalActionEngine(scheduler, memory.services(), Logger.getAnonymousLogger(), () -> .5,
            (p, token) -> { fail("ordinary text needs no PAPI"); return Optional.empty(); });
        assertEquals(COMMITTED, await(run("[{\"type\":\"message\",\"text\":\"进度 50%，普通文本\"}]")).status());
        assertEquals("进度 50%，普通文本", memory.messages.getFirst());
    }

    @Test void failedPapiAfterCommitDoesNotPretendDebitWasRolledBack() throws Exception {
        assertEquals(POST_COMMIT_FAILED, await(run("""
            [{"type":"economy_withdraw","amount":"128"},
             {"type":"message","text":"余额 %vault_eco_balance%"}]
            """)).status());
        assertEquals(872, memory.balance); assertTrue(memory.messages.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"-1", "0.5", "2147483648"})
    void invalidDelayRejected(String delay) throws Exception {
        assertEquals(ROLLED_BACK, await(run("[{\"type\":\"economy_deposit\",\"amount\":\"1\",\"delay\":" + delay + "}]")).status());
        assertEquals(1000, memory.balance);
    }
    @ParameterizedTest @ValueSource(strings = {"-0.1", "1.1"})
    void invalidChanceRejected(String chance) throws Exception {
        assertEquals(ROLLED_BACK, await(run("[{\"type\":\"economy_deposit\",\"amount\":\"1\",\"chance\":" + chance + "}]")).status());
        assertEquals(1000, memory.balance);
    }
    static void main() { assertTrue(Bukkit.isPrimaryThread(), "player/provider operation must be on main thread"); }
    class RecordingPlayer extends PlayerMock {
        boolean simulateOffline;
        RecordingPlayer(ServerMock server, String name) { super(server, name); }
        @Override public boolean isOnline() { main(); return !simulateOffline && super.isOnline(); }
        @Override public void sendMessage(Component message) { main(); memory.events.add("message"); memory.messages.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message)); }
        @Override public void playSound(Location location, String sound, float volume, float pitch) {
            main(); memory.events.add("sound:" + sound);
        }
        @Override public void closeDialog() { main(); memory.events.add("close"); }
    }
    class Memory implements ActionServices.Economy, ActionServices.PlayerData, ActionServices.Menus {
        double balance = 1000;
        final Map<String, JsonNode> data = new HashMap<>();
        final List<String> events = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<ActionServices.RecoveryRecord> recoveries = new ArrayList<>();
        boolean withdrawFails, incrementFails, dataUndoFails, recoveryFails, menuFails, missingReceipt;
        CompletableFuture<Void> pendingUndo;
        ConditionResult.Status conditionStatus = ConditionResult.Status.PASS;
        CompletableFuture<ActionServices.Change> nextWithdrawal;
        ActionServices services() {
            return new ActionServices(this, this, this, record -> {
                main(); if (recoveryFails) return CompletableFuture.failedFuture(new IllegalStateException("disk failure"));
                recoveries.add(record); return CompletableFuture.completedFuture(null);
            },
                (p, m, b, inputs) -> CompletableFuture.completedFuture(new ConditionResult(conditionStatus, "", "", "")));
        }
        ActionServices.Change receipt(String label, Runnable undo) {
            return new ActionServices.Change() {
                public CompletableFuture<Void> rollback() {
                    main(); events.add("undo:" + label);
                    if (dataUndoFails && label.startsWith("set:")) return CompletableFuture.failedFuture(new IllegalStateException("undo failed"));
                    undo.run();
                    return label.startsWith("set:") && pendingUndo != null ? pendingUndo : CompletableFuture.completedFuture(null);
                }
                public Object recoveryData() { return label; }
            };
        }
        public CompletableFuture<ActionServices.Change> deposit(UUID id, double amount) {
            main(); events.add("deposit"); double old = balance; balance += amount;
            if (missingReceipt) return CompletableFuture.completedFuture(null);
            return CompletableFuture.completedFuture(receipt("deposit", () -> balance = old));
        }
        public CompletableFuture<ActionServices.Change> withdraw(UUID id, double amount) {
            main(); events.add("withdraw");
            if (nextWithdrawal != null) { var pending = nextWithdrawal; nextWithdrawal = null; return pending; }
            if (withdrawFails || balance < amount) return CompletableFuture.failedFuture(new IllegalStateException("withdraw failed"));
            double old = balance; balance -= amount;
            return CompletableFuture.completedFuture(receipt("withdraw", () -> balance = old));
        }
        public CompletableFuture<ActionServices.Change> set(UUID id, String key, JsonNode value) {
            main(); events.add("set:" + key); JsonNode old = data.put(key, value.deepCopy());
            return CompletableFuture.completedFuture(receipt("set:" + key, () -> { if (old == null) data.remove(key); else data.put(key, old); }));
        }
        public CompletableFuture<ActionServices.Change> increment(UUID id, String key, double amount) {
            main(); events.add("increment:" + key);
            if (incrementFails) return CompletableFuture.failedFuture(new IllegalStateException("increment failed"));
            JsonNode old = data.get(key); data.put(key, mapper.valueToTree((old == null ? 0 : old.doubleValue()) + amount));
            return CompletableFuture.completedFuture(receipt("increment:" + key, () -> { if (old == null) data.remove(key); else data.put(key, old); }));
        }
        public CompletableFuture<Void> open(org.bukkit.entity.Player p, String id) {
            main(); events.add("open:" + id);
            return menuFails ? CompletableFuture.failedFuture(new IllegalStateException("menu missing")) : CompletableFuture.completedFuture(null);
        }
        public CompletableFuture<Void> refresh(org.bukkit.entity.Player p, MenuDefinition m) {
            main(); events.add("refresh"); return CompletableFuture.completedFuture(null);
        }
    }
}
