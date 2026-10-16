package dialogcanvas.dialog;

import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DialogRendererTest {
    private ServerMock server;
    private Scheduler scheduler;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        scheduler = new Scheduler(MockBukkit.createMockPlugin());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.close();
        MockBukkit.unmock();
    }

    private io.papermc.paper.registry.data.dialog.action.DialogActionCallback callback(
            DialogRenderer renderer, ActionDefinition button, MenuDefinition menu, String kind) {
        if (menu.getId() == null) menu.setId("test_menu");
        menu.setAfterAction(AfterAction.NONE);
        var session = new DialogRenderer.Session(player, () -> true);
        renderer.activate(session);
        return renderer.callback(button, menu, session, kind,
                net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(100).build());
    }

    @Test
    void callbackRunsEngineOnMainThreadWithOriginalResponseAndButton() {
        var menu = new MenuDefinition();
        var button = new ActionDefinition();
        button.setId("test_button");
        var calls = new AtomicInteger();
        var renderer = new DialogRenderer(scheduler, (actor, definition, action, response) -> {
            assertTrue(Bukkit.isPrimaryThread());
            assertSame(player, actor);
            assertSame(menu, definition);
            assertSame(button, action);
            assertNull(response); // The future engine must validate missing/untrusted responses.
            calls.incrementAndGet();
        }, Logger.getAnonymousLogger());
        var callback = callback(renderer, button, menu, "custom");
        CompletableFuture.runAsync(() -> callback.accept(null, player)).join();
        assertEquals(0, calls.get());
        server.getScheduler().performOneTick();
        assertEquals(1, calls.get());
    }

    @Test
    void callbackRejectsAnotherPlayerAndNonPlayerAudience() {
        var calls = new AtomicInteger();
        var renderer = new DialogRenderer(scheduler, (actor, menu, button, response) -> calls.incrementAndGet(),
                Logger.getAnonymousLogger());
        var callback = callback(renderer, new ActionDefinition(), new MenuDefinition(), "custom");
        callback.accept(null, server.addPlayer());
        callback.accept(null, server.getConsoleSender());
        assertEquals(0, calls.get());
    }

    @Test
    void callbackIgnoresDuplicateUntilAsyncEngineFinishes() {
        var calls = new AtomicInteger();
        var pending = new java.util.concurrent.atomic.AtomicReference<>(new CompletableFuture<Void>());
        var engine = new dialogcanvas.engine.ActionEngine() {
            public void execute(org.bukkit.entity.Player p, MenuDefinition m, ActionDefinition a,
                    io.papermc.paper.dialog.DialogResponseView response) { fail("async entry expected"); }
            public CompletableFuture<Void> executeAsync(org.bukkit.entity.Player p, MenuDefinition m, ActionDefinition a,
                    io.papermc.paper.dialog.DialogResponseView response) {
                assertTrue(Bukkit.isPrimaryThread()); calls.incrementAndGet(); return pending.get();
            }
        };
        var button = new ActionDefinition(); button.setId("buy");
        var renderer = new DialogRenderer(scheduler, engine, Logger.getAnonymousLogger());
        var callback = callback(renderer, button, new MenuDefinition(), "custom");
        callback.accept(null, player); callback.accept(null, player); assertEquals(1, calls.get());
        pending.get().complete(null);
        pending.set(new CompletableFuture<>());
        callback.accept(null, player); callback.accept(null, player); assertEquals(2, calls.get());
        pending.get().completeExceptionally(new IllegalStateException("expected test failure"));
        pending.set(new CompletableFuture<>());
        callback.accept(null, player); assertEquals(3, calls.get()); pending.get().complete(null);
    }

    @Test
    void invalidIconKeepsTextAndWarnsWithButtonContext() {
        var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        logger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { records.add(record); }
            public void flush() {} public void close() {}
        });
        var renderer = new DialogRenderer(scheduler, (p, m, a, r) -> {}, logger);
        var menu = new MenuDefinition(); menu.setId("icon_demo");
        var button = new ActionDefinition(); button.setId("invalid");
        button.setLabel(MenuJson.createMapper().readValue("\"&c保留文字\"", TextSpec.class));
        var icon = new ButtonIconDefinition(); icon.setMaterial("DCMENU_INVALID_ITEM"); button.setIcon(icon);
        assertEquals(new TextRenderer().render(button.getLabel()), renderer.buttonLabel(button, menu));
        assertEquals(1, records.size()); assertEquals(java.util.logging.Level.WARNING, records.getFirst().getLevel());
        assertTrue(records.getFirst().getMessage().contains("button=invalid"));
        button.setIcon(null); renderer.buttonLabel(button, menu); assertEquals(1, records.size());
    }

    @Test
    void allDemoButtonsKeepMainThreadCallbacksIncludingInvalidIcon() throws Exception {
        final MenuDefinition menu;
        try (var input = getClass().getResourceAsStream("/examples/icon-buttons.json")) {
            assertNotNull(input);
            menu = MenuJson.createMapper().readValue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), MenuDefinition.class);
        }
        var calls = new java.util.ArrayList<String>();
        var renderer = new DialogRenderer(scheduler, (p, m, button, response) -> {
            assertTrue(Bukkit.isPrimaryThread()); assertSame(player, p); assertSame(menu, m); calls.add(button.getId());
        }, Logger.getAnonymousLogger());
        for (var button : menu.getActions()) {
            assertNotNull(renderer.buttonLabel(button, menu));
            callback(renderer, button, menu, "custom").accept(null, player);
        }
        assertEquals(menu.getActions().stream().map(ActionDefinition::getId).toList(), calls);
    }

    @Test
    void stoppedSchedulerPreventsCallbackExecution() {
        var calls = new AtomicInteger();
        var renderer = new DialogRenderer(scheduler, (actor, menu, button, response) -> calls.incrementAndGet(),
                Logger.getAnonymousLogger());
        var callback = callback(renderer, new ActionDefinition(), new MenuDefinition(), "custom");
        scheduler.close();
        callback.accept(null, player);
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsDirectConversionOnWorkerThread() {
        var renderer = new DialogRenderer(scheduler, (actor, menu, button, response) -> {}, Logger.getAnonymousLogger());
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class,
                () -> renderer.render(new MenuDefinition(), player))).join();
    }

    @Test
    void inventoryCapsCountOnlyMatchingStorageItemsAndRecalculateFromFreshDefinitions() {
        var mapper = MenuJson.createMapper();
        String json = """
            {"id":"inventory_shop","dialogType":"notice","title":"Shop","inputs":[
              {"type":"number_range","key":"sell","label":"Sell","start":0,"end":2304,"initial":0,"step":1,"inventoryMaximumMaterial":"OAK_LOG"},
              {"type":"text","key":"manual","label":"Sell","numeric":true,"integer":true,"minimum":0,"maximum":2304,"initial":"0","inventoryMaximumMaterial":"OAK_LOG"}]}
            """;
        var renderer = new DialogRenderer(scheduler, (p, m, a, r) -> {}, Logger.getAnonymousLogger());
        var empty = renderer.bindPlayerInputs(mapper.readValue(json, MenuDefinition.class), player);
        assertEquals(0d, ((InputDefinition.NumberRange) empty.getInputs().getFirst()).getEnd());
        assertEquals(0d, ((InputDefinition.Text) empty.getInputs().get(1)).getMaximum());
        player.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.OAK_LOG, 64));
        player.getInventory().setItem(1, new org.bukkit.inventory.ItemStack(org.bukkit.Material.OAK_LOG, 11));
        player.getInventory().setItemInOffHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.OAK_LOG, 6));
        var named = new org.bukkit.inventory.ItemStack(org.bukkit.Material.OAK_LOG, 4);
        var meta = named.getItemMeta(); meta.displayName(net.kyori.adventure.text.Component.text("Keep me")); named.setItemMeta(meta);
        player.getInventory().setItem(2, named);
        player.getInventory().setItem(3, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BIRCH_LOG, 32));
        var stocked = renderer.bindPlayerInputs(mapper.readValue(json, MenuDefinition.class), player);
        assertEquals(75d, ((InputDefinition.NumberRange) stocked.getInputs().getFirst()).getEnd());
        assertEquals(75d, ((InputDefinition.Text) stocked.getInputs().get(1)).getMaximum());
        player.getInventory().setItem(1, null);
        var refreshed = renderer.bindPlayerInputs(mapper.readValue(json, MenuDefinition.class), player);
        assertEquals(64d, ((InputDefinition.NumberRange) refreshed.getInputs().getFirst()).getEnd());
        assertEquals(75d, ((InputDefinition.NumberRange) stocked.getInputs().getFirst()).getEnd());
    }

    @Test
    void showReportsInvalidDocumentThroughFuture() {
        var renderer = new DialogRenderer(scheduler, (actor, menu, button, response) -> {}, Logger.getAnonymousLogger());
        var completion = renderer.show(player, new MenuDefinition());
        assertTrue(completion.isCompletedExceptionally());
        assertThrows(java.util.concurrent.CompletionException.class, completion::join);
    }

    @Test
    void showFromWorkerQueuesConversionOnMainThread() {
        var renderer = new DialogRenderer(scheduler, (actor, menu, button, response) -> {}, Logger.getAnonymousLogger());
        var completion = CompletableFuture.supplyAsync(() -> renderer.show(player, new MenuDefinition())).join();
        assertFalse(completion.isDone());
        server.getScheduler().performOneTick();
        assertTrue(completion.isCompletedExceptionally());
    }

    @ParameterizedTest
    @EnumSource(value = DialogType.class, names = {"NOTICE", "CONFIRMATION", "MULTI_ACTION"})
    void constructsNativeDialogWhenProvidersAreAvailable(DialogType type) {
        Assumptions.assumeTrue(
                ServiceLoader.load(io.papermc.paper.registry.data.dialog.DialogInstancesProvider.class).findFirst().isPresent()
                && ServiceLoader.load(io.papermc.paper.registry.data.InlinedRegistryBuilderProvider.class).findFirst().isPresent(),
                "MockBukkit currently lacks Paper's dynamic Dialog builder providers; verify on target Paper");
        var menu = new MenuDefinition();
        menu.setDialogType(type);
        menu.setTitle(MenuJson.createMapper().readValue("\"Title\"", TextSpec.class));
        var button = new ActionDefinition();
        button.setLabel(MenuJson.createMapper().readValue("\"OK\"", TextSpec.class));
        menu.setYesButton(button);
        menu.setNoButton(button);
        menu.setActions(java.util.List.of(button));
        var renderer = new DialogRenderer(scheduler, (actor, definition, action, response) -> {}, Logger.getAnonymousLogger());
        assertNotNull(renderer.render(menu, player));
    }
}
