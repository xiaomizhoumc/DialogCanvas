package dialogcanvas.engine;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ClickGuardTest {
    final ClickGuard guard = new ClickGuard();
    final UUID player = UUID.randomUUID();
    @Test void pendingClickIgnoredAndSuccessUnlocks() {
        var pending = new CompletableFuture<Void>();
        var first = guard.execute(player, "buy", () -> pending);
        assertFalse(guard.execute(player, "buy", () -> { fail("duplicate must not execute"); return null; }).join());
        pending.complete(null); assertTrue(first.join());
        assertTrue(guard.execute(player, "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
    @Test void asynchronousFailureUnlocks() {
        var pending = new CompletableFuture<Void>(); var first = guard.execute(player, "buy", () -> pending);
        pending.completeExceptionally(new IllegalStateException("failed"));
        assertThrows(CompletionException.class, first::join);
        assertTrue(guard.execute(player, "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
    @Test void synchronousThrowAndNullFutureUnlock() {
        assertThrows(CompletionException.class, () -> guard.execute(player, "buy", () -> { throw new IllegalStateException(); }).join());
        assertThrows(CompletionException.class, () -> guard.execute(player, "buy", () -> null).join());
        assertTrue(guard.execute(player, "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
    @Test void differentPlayersAndButtonsDoNotBlockEachOther() {
        var pending = new CompletableFuture<Void>(); guard.execute(player, "buy", () -> pending);
        assertTrue(guard.execute(UUID.randomUUID(), "buy", () -> CompletableFuture.completedFuture(null)).join());
        assertTrue(guard.execute(player, "cancel", () -> CompletableFuture.completedFuture(null)).join());
        pending.complete(null);
    }
    @Test void callerCancellationDoesNotUnlock() {
        var pending = new CompletableFuture<Void>(); var result = guard.execute(player, "buy", () -> pending);
        result.cancel(false);
        assertFalse(guard.execute(player, "buy", () -> { fail(); return null; }).join());
        pending.complete(null);
        assertTrue(guard.execute(player, "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
    @Test void cancellingUnderlyingOperationUnlocks() {
        var pending = new CompletableFuture<Void>(); var result = guard.execute(player, "buy", () -> pending);
        pending.cancel(false); assertTrue(result.isCompletedExceptionally());
        assertTrue(guard.execute(player, "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
    @Test void simultaneousCallsOnlyExecuteOnce() throws Exception {
        var pending = new CompletableFuture<Void>(); var calls = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = IntStream.range(0, 32).mapToObj(i -> workers.submit(() -> guard.execute(player, "buy", () -> {
                calls.incrementAndGet(); return pending;
            }))).toList();
            for (var submitted : submissions) submitted.get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get()); pending.complete(null);
        }
    }

    @Test void structuredMenuKeysAvoidCrossMenuAndDelimiterCollisions() {
        var pending = new CompletableFuture<Void>();
        guard.execute(player, "shop", "buy", () -> pending);
        assertFalse(guard.execute(player, "shop", "buy", () -> { fail(); return null; }).join());
        assertTrue(guard.execute(player, "other_shop", "buy", () -> CompletableFuture.completedFuture(null)).join());
        guard.execute(player, "a:b", "c", () -> pending);
        assertTrue(guard.execute(player, "a", "b:c", () -> CompletableFuture.completedFuture(null)).join());
        assertTrue(guard.execute(UUID.randomUUID(), "shop", "buy", () -> CompletableFuture.completedFuture(null)).join());
        pending.complete(null);
    }

    @Test void fatalSynchronousErrorStillReleasesOwnership() {
        assertThrows(AssertionError.class, () -> guard.execute(player, "shop", "buy", () -> { throw new AssertionError("test"); }));
        assertTrue(guard.execute(player, "shop", "buy", () -> CompletableFuture.completedFuture(null)).join());
    }
}
