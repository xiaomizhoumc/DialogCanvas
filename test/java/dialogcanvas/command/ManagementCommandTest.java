package dialogcanvas.command;

import dialogcanvas.model.MenuJson;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.web.EmbeddedHttpServer;
import dialogcanvas.web.menu.*;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ManagementCommandTest {
    @Test void auditRecordsActualAsyncCommandResultAndDoesNotRecordArguments() throws Exception {
        var audit = new dialogcanvas.audit.AuditLogger(directory, Logger.getAnonymousLogger()); var completed = new CompletableFuture<Void>();
        var subject = new ManagementCommand(scheduler, () -> menus,
                () -> new EmbeddedHttpServer.Status(EmbeddedHttpServer.State.RUNNING, "http://127.0.0.1:8080/", ""),
                () -> true, (actor, id) -> completed, Logger.getAnonymousLogger()); subject.auditLogger(audit);
        try {
            subject.execute(new Source(player), new String[]{"open", "private-command-argument"}); assertEquals(0, audit.stats().accepted());
            completed.completeExceptionally(new IllegalStateException("private-command-error"));
            subject.execute(new Source(server.getConsoleSender()), new String[]{"data", "restore", "private-path-argument"});
            audit.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
            String raw = Files.readString(directory.resolve("logs/audit.log")); var rows = raw.lines().map(MenuJson.createMapper()::readTree).toList();
            assertEquals(2, rows.size()); assertEquals("FAILED", rows.getFirst().path("result").asString()); assertEquals("REJECTED", rows.getLast().path("result").asString());
            assertEquals(player.getUniqueId().toString(), rows.getFirst().path("actor").asString()); assertEquals("console", rows.getLast().path("actor").asString());
            assertFalse(raw.contains("private-"));
        } finally { audit.closeAsync().join(); }
    }
    @TempDir Path directory;
    ServerMock server;
    PlayerMock player;
    Scheduler scheduler;
    MenuRepository menus;
    ManagementCommand command;
    AtomicBoolean ready = new AtomicBoolean(true);
    AtomicReference<String> opened = new AtomicReference<>();

    @BeforeEach void setup() {
        server = MockBukkit.mock(); player = server.addPlayer(); player.setOp(true);
        scheduler = new Scheduler(MockBukkit.createMockPlugin());
        menus = new MenuRepository(new FileStorage(directory), new MenuValidator(new MenuEnvironment(Set.of("STONE"), Set.of())));
        menus.initialize().join();
        command = new ManagementCommand(scheduler, () -> menus,
                () -> new EmbeddedHttpServer.Status(EmbeddedHttpServer.State.RUNNING, "http://127.0.0.1:8080/", ""),
                ready::get, (actor, id) -> {
                    assertTrue(Bukkit.isPrimaryThread()); opened.set(id); actor.sendMessage("opened");
                    return CompletableFuture.completedFuture(null);
                }, Logger.getAnonymousLogger());
    }
    @AfterEach void cleanup() { menus.close(); scheduler.close(); MockBukkit.unmock(); }

    @Test void dispatchFailureLogsFixedDiagnosticWithoutPrivateCauseOrPath() {
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { records.add(record); }
            public void flush() {} public void close() {}
        });
        var subject = new ManagementCommand(scheduler, () -> menus, () -> null, () -> true,
                (actor, id) -> CompletableFuture.completedFuture(null), logger);
        CommandSourceStack broken = new CommandSourceStack() {
            public CommandSender getSender() { throw new IllegalStateException("test-private E:\\server\\secret.yml"); }
            public Location getLocation() { throw new UnsupportedOperationException(); }
            public Entity getExecutor() { return null; }
            public CommandSourceStack withLocation(Location location) { return this; }
            public CommandSourceStack withExecutor(Entity entity) { return this; }
        };
        subject.execute(broken, new String[]{"help"});
        assertEquals(1, records.size()); assertNull(records.getFirst().getThrown());
        assertTrue(records.getFirst().getMessage().contains("MANAGEMENT_OPERATION_FAILED"));
        assertFalse(records.getFirst().getMessage().contains("test-private")); assertFalse(records.getFirst().getMessage().contains("E:\\server"));
    }

    private record Source(CommandSender sender) implements CommandSourceStack {
        @Override public CommandSender getSender() { assertTrue(Bukkit.isPrimaryThread()); return sender; }
        @Override public Location getLocation() { throw new UnsupportedOperationException(); }
        @Override public Entity getExecutor() { return sender instanceof Entity entity ? entity : null; }
        @Override public CommandSourceStack withLocation(Location location) { throw new UnsupportedOperationException(); }
        @Override public CommandSourceStack withExecutor(Entity entity) { throw new UnsupportedOperationException(); }
    }
    private List<String> messages() {
        var result = new ArrayList<String>(); String text;
        while ((text = player.nextMessage()) != null) result.add(text);
        return result;
    }
    private List<String> awaitMessage(String expected) throws Exception {
        var result = new ArrayList<String>(); long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            server.getScheduler().performTicks(1); result.addAll(messages());
            if (result.stream().anyMatch(text -> text.contains(expected))) return result;
            Thread.sleep(5);
        }
        fail("Command reply not received: " + expected + " / " + result); return result;
    }
    private void create(String id) {
        menus.create(MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"title\":\"original\",\"dialogType\":\"notice\"}")).join();
    }
    private void yaml(String id, String type) throws Exception {
        Files.writeString(directory.resolve("manual-menus").resolve(id + ".yml"),
                "schemaVersion: 1\nid: " + id + "\ntitle: updated\ndialogType: " + type + "\n");
    }

    @Test void rootShowsProductionHelpAndOldDebugCommandsAreUnavailable() {
        command.execute(new Source(player), new String[0]);
        String help = String.join("\n", messages());
        assertTrue(help.contains("/dcmenu open <菜单ID>")); assertTrue(help.contains("/dcmenu reload <菜单ID>"));
        assertTrue(help.contains("按 Tab")); assertFalse(help.contains("测试")); assertFalse(help.contains("test|icons"));
        for (String old : List.of("test", "icons", "shop", "menu")) {
            command.execute(new Source(player), new String[]{old}); assertTrue(player.nextMessage().contains("未知子命令"));
        }
        assertNull(opened.get());
    }
    @Test void dataMaintenanceIsConsoleOnlyAndDoesNotAcceptAnArbitraryPath() {
        command.execute(new Source(player), new String[]{"data", "archive"}); assertTrue(player.nextMessage().contains("仅允许"));
        assertEquals(List.of("archive", "dry-run", "restore"), command.suggest(new Source(server.getConsoleSender()), new String[]{"data", ""}));
        command.execute(new Source(server.getConsoleSender()), new String[]{"data", "restore"}); assertTrue(server.getConsoleSender().nextMessage().contains("用法"));
    }
    @Test void suggestionsUseCommittedIdsAndUpdateAfterDeletionWithoutFileIo() {
        var source = new Source(server.getConsoleSender());
        assertEquals(List.of("data", "edit", "editor", "help", "list", "main", "open", "reload"), command.suggest(source, new String[]{""}));
        assertEquals(List.of("reload"), command.suggest(source, new String[]{"RE"}));
        create("alpha"); create("beta");
        assertEquals(List.of("alpha"), command.suggest(source, new String[]{"open", "a"}));
        assertEquals(List.of("alpha", "beta"), command.suggest(source, new String[]{"reload", ""}));
        assertTrue(command.suggest(source, new String[]{"open", "alpha", ""}).contains(player.getName()));
        String revision = menus.get("alpha").join().get("revision").asString(); menus.delete("alpha", revision).join();
        assertEquals(List.of("beta"), command.suggest(source, new String[]{"open", ""}));
        assertTrue(command.suggest(null, new String[]{"open", ""}).isEmpty());
        menus.close(); assertTrue(command.suggest(source, new String[]{"open", ""}).isEmpty());
    }
    @Test void mainOpensOnlyTheCommittedMainMenu() {
        command.execute(new Source(player), new String[]{"main"}); assertTrue(player.nextMessage().contains("尚未设置"));
        menus.create(MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"home\",\"title\":\"Home\",\"dialogType\":\"notice\",\"shortcutEnabled\":true}")).join();
        command.execute(new Source(player), new String[]{"main"}); assertEquals("home", opened.get());
    }
    @Test void permissionAndStartupGateExecution() {
        player.setOp(false); assertTrue(command.canUse(player)); // Public root; actual execution still checks permissions.
        command.execute(new Source(player), new String[0]); assertTrue(player.nextMessage().contains("没有"));
        player.setOp(true); assertTrue(command.canUse(player)); ready.set(false);
        command.execute(new Source(player), new String[0]); assertTrue(player.nextMessage().contains("尚未就绪"));
    }
    @Test void ordinaryPlayersCanOpenMainButCannotManageOrDiscoverOtherMenus() {
        player.setOp(false);
        player.addAttachment(MockBukkit.createMockPlugin(), "dcmenu.shortcut", true);
        menus.create(MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"home\",\"title\":\"Home\",\"dialogType\":\"notice\",\"shortcutEnabled\":true}")).join();
        assertTrue(command.canUse(player));
        assertEquals(List.of("main"), command.suggest(new Source(player), new String[]{""}));
        assertTrue(command.suggest(new Source(player), new String[]{"open", ""}).isEmpty());
        command.execute(new Source(player), new String[]{"main"}); assertEquals("home", opened.get());
        messages(); opened.set(null);
        for (String action : List.of("open", "editor", "reload")) {
            command.execute(new Source(player), new String[]{action}); assertTrue(player.nextMessage().contains("没有"));
        }
        assertNull(opened.get());
    }
    @Test void editorAndConsoleOpenHaveClearPublicMessages() {
        command.execute(new Source(server.getConsoleSender()), new String[]{"editor"});
        assertTrue(server.getConsoleSender().nextMessage().contains("http://127.0.0.1:8080/"));
        server.getConsoleSender().nextMessage();
        command.execute(new Source(server.getConsoleSender()), new String[]{"open", "alpha"});
        assertTrue(server.getConsoleSender().nextMessage().contains("游戏内"));
        command.execute(new Source(player), new String[]{"editor", "extra"}); assertTrue(player.nextMessage().contains("用法"));
    }
    @Test void playerAccessFromAsyncExecutionRunsOnMainThread() throws Exception {
        CompletableFuture.runAsync(() -> command.execute(new Source(player), new String[]{"open", "alpha"})).join();
        awaitMessage("opened"); assertEquals("alpha", opened.get());
    }
    @Test void asyncCommandTreeAndSuggestionsNeverReadPlayerPermissions() throws Exception {
        PlayerMock guarded = new PlayerMock(server, "guarded") {
            @Override public boolean hasPermission(String permission) {
                assertTrue(Bukkit.isPrimaryThread()); return super.hasPermission(permission);
            }
        };
        assertTrue(CompletableFuture.supplyAsync(() -> command.canUse(guarded)).get());
        assertTrue(CompletableFuture.supplyAsync(() -> command.suggest(new Source(guarded), new String[]{""})).get().isEmpty());
        assertTrue(CompletableFuture.supplyAsync(() -> command.suggest(new Source(guarded), new String[]{"open", "alpha", ""})).get().isEmpty());
    }
    @Test void malformedAndUnavailableCommandsDoNotOpenMenus() {
        command.execute(new Source(player), new String[]{"open"}); assertTrue(player.nextMessage().contains("按 Tab"));
        command.execute(new Source(player), new String[]{"reload", "id", "extra"}); assertTrue(player.nextMessage().contains("用法"));
        menus.close(); command.execute(new Source(player), new String[]{"open", "alpha"}); assertTrue(player.nextMessage().contains("尚未就绪"));
        assertNull(opened.get());
    }
    @Test void singleReloadKeepsExistingSuccessContractAndImportsYaml() throws Exception {
        create("alpha"); yaml("alpha", "notice");
        command.execute(new Source(player), new String[]{"reload", "alpha"});
        awaitMessage("YAML 正式数据重载成功：alpha"); assertEquals("updated", menus.get("alpha").join().get("menu").get("title").asString());
    }
    @Test void batchReloadReportsPartialSuccessWithoutDumpingJson() throws Exception {
        create("alpha"); create("beta"); yaml("alpha", "notice"); yaml("beta", "invalid_type");
        command.execute(new Source(player), new String[]{"reload"});
        String output = String.join("\n", awaitMessage("YAML 重载完成"));
        assertTrue(output.contains("成功 1，失败 1")); assertTrue(output.contains("beta：导入失败"));
        assertFalse(output.contains("\"items\"")); assertEquals("original", menus.get("beta").join().get("menu").get("title").asString());
    }

    private List<String> consoleMessages() {
        var messages = new ArrayList<String>(); String text;
        while ((text = server.getConsoleSender().nextMessage()) != null) messages.add(text);
        return messages;
    }
    private void ticksUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        assertTrue(condition.getAsBoolean());
    }
    @Test void listPaginatesOnlyLoadedIdsInStableOrderAndDoesNotImportManualFiles() throws Exception {
        for (int index = 0; index < 21; index++) create(String.format(Locale.ROOT, "menu_%02d", index));
        Files.writeString(directory.resolve("manual-menus/not_loaded.yml"), "id: not_loaded\n");
        var source = new Source(server.getConsoleSender()); command.execute(source, new String[]{"list"});
        var first = consoleMessages(); assertTrue(first.getFirst().contains("第 1/3 页，共 21 个"));
        assertEquals(10, first.stream().filter(line -> line.matches(".*\\d+\\. menu_.*")).count());
        assertTrue(first.get(1).endsWith("menu_00")); assertTrue(first.get(10).endsWith("menu_09")); assertTrue(first.getLast().contains("list 2"));
        command.execute(source, new String[]{"list", "3"}); var last = consoleMessages();
        assertEquals(2, last.size()); assertTrue(last.getLast().endsWith("21. menu_20")); assertFalse(String.join("", first).contains("not_loaded"));
        assertEquals(List.of("1", "2", "3"), command.suggest(source, new String[]{"list", ""}));
    }
    @Test void emptyListAndInvalidPagesGiveUsefulMessages() {
        var source = new Source(server.getConsoleSender()); command.execute(source, new String[]{"list"}); assertTrue(consoleMessages().getFirst().contains("尚无已加载"));
        for (String page : List.of("0", "-1", "abc", "2147483648", "99999999999999999999", "1.5")) {
            command.execute(source, new String[]{"list", page}); assertTrue(consoleMessages().getFirst().contains("从 1 开始"));
        }
        command.execute(source, new String[]{"list", "2"}); assertTrue(consoleMessages().getFirst().contains("超出范围"));
        command.execute(source, new String[]{"list", "1", "extra"}); assertTrue(consoleMessages().getFirst().contains("用法"));
    }
    @Test void consoleAndAdminPlayerCanOpenForAnExactOnlineTargetOnMain() {
        var target = server.addPlayer("Recipient"); var captured = new AtomicReference<PlayerMock>();
        var subject = new ManagementCommand(scheduler, () -> menus, () -> null, () -> true, (actor, id) -> {
            assertTrue(Bukkit.isPrimaryThread()); captured.set((PlayerMock) actor); assertEquals("alpha", id); return CompletableFuture.completedFuture(null);
        }, Logger.getAnonymousLogger());
        subject.execute(new Source(server.getConsoleSender()), new String[]{"open", "alpha", "Recipient"});
        assertSame(target, captured.get()); assertTrue(consoleMessages().getFirst().contains("已向在线玩家 Recipient 展示菜单 alpha"));
        captured.set(null); subject.execute(new Source(player), new String[]{"open", "alpha", "Recipient"});
        assertSame(target, captured.get()); assertTrue(player.nextMessage().contains("已向在线玩家"));
    }
    @Test void offlineOrPartialNamesAreRejectedWithoutAnyOpenCallAndPlayersAreCompleted() {
        var recipient = server.addPlayer("Recipient"); var source = new Source(server.getConsoleSender());
        assertEquals(List.of("Recipient"), command.suggest(source, new String[]{"open", "alpha", "Reci"}));
        command.execute(source, new String[]{"open", "alpha", "Recip"}); assertNull(opened.get()); assertTrue(consoleMessages().getFirst().contains("完整玩家名"));
        recipient.disconnect(); command.execute(source, new String[]{"open", "alpha", "Recipient"});
        assertNull(opened.get()); assertTrue(consoleMessages().getFirst().contains("在线玩家"));
        assertFalse(command.suggest(source, new String[]{"open", "alpha", ""}).contains("Recipient"));
    }
    @Test void editShowsSameAddressAsEditorAndDoesNotContainCredentials() {
        var source = new Source(server.getConsoleSender()); command.execute(source, new String[]{"editor"}); var expected = consoleMessages();
        command.execute(source, new String[]{"edit"}); assertEquals(expected, consoleMessages());
        assertTrue(expected.getFirst().endsWith("http://127.0.0.1:8080/"));
        assertFalse(String.join("", expected).matches(".*(Token|password|csrf|\\?).*"));
    }
    @Test void delegatedPermissionsOnlyExposeAndExecuteTheirOwnCommands() {
        player.setOp(false); var permissions = player.addAttachment(MockBukkit.createMockPlugin());
        permissions.setPermission("dcmenu.admin", false); permissions.setPermission("dcmenu.shortcut", false); permissions.setPermission("dcmenu.command.list", true);
        create("private_menu"); var source = new Source(player);
        assertEquals(List.of("help", "list"), command.suggest(source, new String[]{""}));
        command.execute(source, new String[]{"list"}); assertTrue(String.join("", messages()).contains("private_menu"));
        for (String action : List.of("open", "reload", "editor", "edit", "data", "main")) {
            command.execute(source, new String[]{action}); assertTrue(player.nextMessage().contains("没有"));
            assertTrue(command.suggest(source, new String[]{action, ""}).isEmpty());
        }
        command.execute(source, new String[]{"help"}); String help = String.join("", messages()); assertTrue(help.contains("/dcmenu list")); assertFalse(help.contains("/dcmenu open"));
    }
    @Test void editorDelegationCoversAliasButNeverGrantsMenuDiscovery() {
        player.setOp(false); player.addAttachment(MockBukkit.createMockPlugin(), "dcmenu.command.editor", true);
        var source = new Source(player); assertTrue(command.suggest(source, new String[]{""}).containsAll(List.of("edit", "editor")));
        assertTrue(command.suggest(source, new String[]{"open", ""}).isEmpty());
        command.execute(source, new String[]{"edit"}); assertTrue(player.nextMessage().contains("http://127.0.0.1:8080/"));
    }
    @Test void aliasRegistrationChecksDmeAndNeverTakesAnOccupiedLabel() {
        var checked = new ArrayList<String>(); var warnings = new ArrayList<String>();
        assertEquals(List.of("dialogcanvas", "dme"), ManagementCommand.registrationAliases(label -> { checked.add(label); return false; }, warnings::add));
        assertEquals(List.of("dme"), checked); assertTrue(warnings.isEmpty());
        assertEquals(List.of("dialogcanvas"), ManagementCommand.registrationAliases(label -> true, warnings::add));
        assertEquals(1, warnings.size()); assertTrue(warnings.getFirst().contains("已被占用"));
    }
    @Test void manifestPreservesPermissionNamespaceAndSafeLeafDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(stream); Map<?, ?> descriptor = new org.yaml.snakeyaml.Yaml().load(stream); Map<?, ?> permissions = (Map<?, ?>) descriptor.get("permissions");
            assertEquals("op", ((Map<?, ?>) permissions.get("dcmenu.admin")).get("default"));
            assertEquals(true, ((Map<?, ?>) permissions.get("dcmenu.shortcut")).get("default"));
            for (String action : List.of("help", "editor", "list", "open", "reload", "data"))
                assertEquals(false, ((Map<?, ?>) permissions.get("dcmenu.command." + action)).get("default"));
            assertTrue(permissions.keySet().stream().noneMatch(key -> key.toString().startsWith("dialogeditor.")));
        }
    }
    @Test void consoleGetsSuccessOnlyAfterActualDisplayFutureAndAuditCompletion() throws Exception {
        var held = new CompletableFuture<Void>(); var target = server.addPlayer("Recipient");
        var audit = new dialogcanvas.audit.AuditLogger(directory, Logger.getAnonymousLogger());
        var subject = new ManagementCommand(scheduler, () -> menus, () -> null, () -> true, (actor, id) -> held, Logger.getAnonymousLogger()); subject.auditLogger(audit);
        try {
            subject.execute(new Source(server.getConsoleSender()), new String[]{"open", "alpha", target.getName()}); assertTrue(consoleMessages().isEmpty()); assertEquals(0, audit.stats().accepted());
            CompletableFuture.runAsync(() -> held.complete(null)).join(); server.getScheduler().performOneTick();
            assertTrue(consoleMessages().getFirst().contains("已向在线玩家 Recipient")); audit.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var rows = Files.readAllLines(directory.resolve("logs/audit.log")).stream().map(MenuJson.createMapper()::readTree).toList();
            assertEquals(1, rows.size()); assertEquals("SUCCESS", rows.getFirst().path("result").asString());
        } finally { audit.closeAsync().join(); }
    }
    @Test void disconnectDuringDisplayReportsFailureToConsoleAndCancelledAudit() throws Exception {
        var held = new CompletableFuture<Void>(); var target = server.addPlayer("Recipient");
        var audit = new dialogcanvas.audit.AuditLogger(directory, Logger.getAnonymousLogger());
        var subject = new ManagementCommand(scheduler, () -> menus, () -> null, () -> true, (actor, id) -> held, Logger.getAnonymousLogger()); subject.auditLogger(audit);
        try {
            subject.execute(new Source(server.getConsoleSender()), new String[]{"open", "alpha", target.getName()}); target.disconnect(); held.cancel(false);
            assertTrue(consoleMessages().getFirst().contains("已离线")); audit.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var row = MenuJson.createMapper().readTree(Files.readAllLines(directory.resolve("logs/audit.log")).getFirst());
            assertEquals("FAILED", row.path("result").asString()); assertEquals("OPERATION_CANCELLED", row.path("code").asString());
        } finally { audit.closeAsync().join(); }
    }
    @Test void unknownMenuFailureReachesConsoleWithoutClaimingSuccess() throws Exception {
        var subject = new ManagementCommand(scheduler, () -> menus, () -> null, () -> true,
                (actor, id) -> CompletableFuture.failedFuture(new ApiException(404, "MENU_NOT_FOUND", "菜单不存在")), Logger.getAnonymousLogger());
        subject.execute(new Source(server.getConsoleSender()), new String[]{"open", "missing", player.getName()});
        var output = consoleMessages(); assertEquals(1, output.size()); assertTrue(output.getFirst().contains("MENU_NOT_FOUND")); assertFalse(output.getFirst().contains("已向"));
        var synchronous = new ManagementCommand(scheduler, () -> menus, () -> null, () -> true,
                (actor, id) -> { throw new IllegalStateException("private-open-error"); }, Logger.getAnonymousLogger());
        synchronous.execute(new Source(server.getConsoleSender()), new String[]{"open", "missing", player.getName()});
        var failure = consoleMessages(); assertEquals(1, failure.size()); assertTrue(failure.getFirst().contains("未完成")); assertFalse(failure.getFirst().contains("private-open-error"));
    }
    @Test void serviceNotReadyBlocksListAndTargetOpeningAndSuggestions() {
        ready.set(false); var source = new Source(server.getConsoleSender());
        for (String action : List.of("list", "open", "edit")) {
            command.execute(source, new String[]{action, "alpha", player.getName()}); assertTrue(consoleMessages().getFirst().contains("尚未就绪"));
        }
        assertTrue(command.suggest(source, new String[]{"open", ""}).isEmpty()); assertTrue(command.suggest(source, new String[]{"open", "alpha", ""}).isEmpty());
        ready.set(true); menus.close(); command.execute(source, new String[]{"list"}); assertTrue(consoleMessages().getFirst().contains("菜单服务尚未就绪"));
        command.execute(source, new String[]{"open", "alpha", player.getName()}); assertTrue(consoleMessages().getFirst().contains("菜单服务尚未就绪")); assertNull(opened.get());
    }
    @Test void asyncEntryCapturesArgumentsAndDispatchesTargetOnMain() throws Exception {
        var target = server.addPlayer("Recipient"); String[] args = {"open", "captured", target.getName()};
        CompletableFuture.runAsync(() -> command.execute(new Source(server.getConsoleSender()), args)).join(); args[1] = "mutated"; args[2] = "offline";
        ticksUntil(() -> opened.get() != null); assertEquals("captured", opened.get());
    }
}
