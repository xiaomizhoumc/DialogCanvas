package dialogcanvas.dialog;

import dialogcanvas.engine.ActionEngine;
import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the exact callback factory used by all native button shapes, without native providers. */
class DialogCallbackAuditTest {
    ServerMock server; Scheduler scheduler; PlayerMock player;
    AtomicInteger calls; AtomicReference<CompletableFuture<Void>> work;
    DialogRenderer renderer; MutableClock clock; Logger logger;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); scheduler = new Scheduler(MockBukkit.createMockPlugin()); player = server.addPlayer();
        calls = new AtomicInteger(); work = new AtomicReference<>(new CompletableFuture<>()); clock = new MutableClock();
        logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        renderer = new DialogRenderer(scheduler, new ActionEngine() {
            public void execute(Player p, MenuDefinition m, ActionDefinition b, DialogResponseView r) { fail("async entry required"); }
            public CompletableFuture<Void> executeAsync(Player p, MenuDefinition m, ActionDefinition b, DialogResponseView r) {
                calls.incrementAndGet(); return work.get();
            }
        }, logger, ignored -> () -> true, clock);
    }
    @AfterEach void cleanup() { work.get().complete(null); renderer.close(); scheduler.close(); MockBukkit.unmock(); }
    @Test void callbackFailureDiagnosticsDoNotAttachSensitiveThirdPartyThrowables() {
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        logger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { records.add(record); }
            public void flush() {} public void close() {}
        });
        var callback = bind(menu("private_test"), session(player, () -> true), "custom", 100);
        callback.accept(null, player);
        work.get().completeExceptionally(new IllegalStateException("fixture-private-callback-payload"));
        assertFalse(records.isEmpty());
        assertTrue(records.stream().allMatch(record -> record.getThrown() == null && !record.getMessage().contains("fixture-private-callback-payload")));
        work.set(new CompletableFuture<>()); callback.accept(null, player);
        assertEquals(2, calls.get(), "A failed callback must still release ClickGuard");
    }
    MenuDefinition menu(String id) {
        var menu = new MenuDefinition(); menu.setId(id); menu.setAfterAction(AfterAction.NONE); return menu;
    }
    ActionDefinition button(String id) { var button = new ActionDefinition(); button.setId(id); return button; }
    DialogRenderer.Session session(Player owner, java.util.function.BooleanSupplier current) {
        var session = new DialogRenderer.Session(owner, current); renderer.activate(session); return session;
    }
    DialogActionCallback bind(MenuDefinition menu, DialogRenderer.Session session, String kind, int uses) {
        return renderer.callback(button("buy"), menu, session, kind,
                ClickCallback.Options.builder().uses(uses).lifetime(Duration.ofSeconds(10)).build());
    }
    @ParameterizedTest @ValueSource(strings = {"noticeAction", "yesButton", "noButton", "actions", "exitAction", "legacy_test"})
    void everyButtonRoleUsesSameGuardIncludingDenialOrTestCallbacks(String role) {
        var menu = menu(role); var session = session(player, () -> true);
        var callback = bind(menu, session, "custom", 100);
        callback.accept(null, player); callback.accept(null, player);
        assertEquals(1, calls.get());
        work.get().complete(null);
        work.set(new CompletableFuture<>()); callback.accept(null, player);
        assertEquals(2, calls.get());
    }
    @Test void refreshedSessionsCannotBypassStableKeyAndOldSessionsCannotRun() {
        var menu = menu("shop"); var old = bind(menu, session(player, () -> true), "custom", 100);
        old.accept(null, player); assertEquals(1, calls.get());
        var fresh = bind(menu, session(player, () -> true), "custom", 100);
        old.accept(null, player); fresh.accept(null, player); assertEquals(1, calls.get());
        work.get().complete(null); work.set(new CompletableFuture<>());
        old.accept(null, player); assertEquals(1, calls.get());
        fresh.accept(null, player); assertEquals(2, calls.get());
    }
    @Test void differentMenusAndPlayersDoNotCollideInClickGuard() {
        bind(menu("first"), session(player, () -> true), "custom", 100).accept(null, player);
        bind(menu("second"), session(player, () -> true), "custom", 100).accept(null, player);
        var other = server.addPlayer();
        bind(menu("second"), session(other, () -> true), "custom", 100).accept(null, other);
        assertEquals(3, calls.get()); // Production adds a separate player-wide business admission gate.
    }
    @Test void rejectsWrongOwnerNonPlayerOfflineAndReconnectedObjectWithSameUuid() {
        var callback = bind(menu("shop"), session(player, () -> true), "custom", 100);
        callback.accept(null, server.addPlayer()); callback.accept(null, server.getConsoleSender());
        var reconnected = new PlayerMock(server, "reconnected", player.getUniqueId());
        callback.accept(null, reconnected);
        player.disconnect(); callback.accept(null, player);
        assertEquals(0, calls.get());
    }
    @Test void versionLeaseRevocationBlocksQueuedClicksBeforeAdmission() {
        var current = new AtomicBoolean(true);
        var callback = bind(menu("shop"), session(player, current::get), "custom", 100);
        CompletableFuture.runAsync(() -> callback.accept(null, player)).join();
        current.set(false); server.getScheduler().performOneTick(); assertEquals(0, calls.get());
    }
    @Test void expirationIsCheckedAtExecutionIncludingExactBoundary() {
        var callback = bind(menu("shop"), session(player, () -> true), "custom", 100);
        clock.now = clock.now.plusSeconds(10); callback.accept(null, player); assertEquals(0, calls.get());
    }
    @Test void usesAreBoundToCallbackAndDuplicateDoesNotSpendLocalUseBudget() {
        var callback = bind(menu("shop"), session(player, () -> true), "custom", 2);
        callback.accept(null, player); callback.accept(null, player); assertEquals(1, calls.get());
        work.get().complete(null); work.set(new CompletableFuture<>());
        callback.accept(null, player); assertEquals(2, calls.get());
        work.get().complete(null); callback.accept(null, player); assertEquals(2, calls.get());
    }
    @Test void closeAndNoneNeverEnterBusinessEngineAndCloseInvalidatesSession() {
        var session = session(player, () -> true); var menu = menu("shop");
        var business = bind(menu, session, "custom", 100);
        bind(menu, session, "none", 100).accept(null, player);
        bind(menu, session, "close", 100).accept(null, player);
        business.accept(null, player); assertEquals(0, calls.get());
    }
    @Test void defaultCloseAfterActionMakesAdmittedCallbackOneShot() {
        var menu = menu("shop"); menu.setAfterAction(null);
        var callback = bind(menu, session(player, () -> true), "custom", 100);
        callback.accept(null, player); work.get().complete(null);
        callback.accept(null, player); assertEquals(1, calls.get());
    }
    @Test void uiInvalidationDoesNotUnlockAnUnsettledOperation() {
        var menu = menu("shop");
        bind(menu, session(player, () -> true), "custom", 100).accept(null, player);
        renderer.invalidate(player);
        var reopened = bind(menu, session(player, () -> true), "custom", 100);
        reopened.accept(null, player); assertEquals(1, calls.get());
        work.get().completeExceptionally(new IllegalStateException("rollback completed"));
        work.set(new CompletableFuture<>()); reopened.accept(null, player); assertEquals(2, calls.get());
    }
    @Test void rendererShutdownRejectsQueuedAndRetainedCallbacks() {
        var callback = bind(menu("shop"), session(player, () -> true), "custom", 100);
        CompletableFuture.runAsync(() -> callback.accept(null, player)).join();
        renderer.close(); server.getScheduler().performOneTick(); callback.accept(null, player);
        assertEquals(0, calls.get());
    }
    @Test void synchronousAndAsynchronousFailuresPermitRetryOnlyAfterSettlement() {
        var attempts = new AtomicInteger();
        var failing = new DialogRenderer(scheduler, new ActionEngine() {
            public void execute(Player p, MenuDefinition m, ActionDefinition b, DialogResponseView r) {}
            public CompletableFuture<Void> executeAsync(Player p, MenuDefinition m, ActionDefinition b, DialogResponseView r) {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("sync failure");
                return CompletableFuture.failedFuture(new IllegalStateException("async failure"));
            }
        }, Logger.getAnonymousLogger());
        try {
            var session = new DialogRenderer.Session(player, () -> true); failing.activate(session);
            var callback = failing.callback(button("buy"), menu("shop"), session, "custom", ClickCallback.Options.builder().uses(100).build());
            callback.accept(null, player); callback.accept(null, player); callback.accept(null, player);
            assertEquals(3, attempts.get());
        } finally { failing.close(); }
    }
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
