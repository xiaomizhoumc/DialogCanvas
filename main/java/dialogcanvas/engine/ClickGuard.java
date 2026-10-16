package dialogcanvas.engine;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Ignores repeated clicks until the original asynchronous operation has settled. */
public final class ClickGuard {
    private record Key(UUID player, String menu, String button) {}
    private final ConcurrentHashMap<Key, Object> locks = new ConcurrentHashMap<>();

    /** true=processed, false=duplicate ignored; failures retain their exception.
     * Cancelling the returned future never unlocks an operation still in progress. */
    public CompletableFuture<Boolean> execute(UUID playerUUID, String buttonId,
            Supplier<? extends CompletableFuture<?>> operation) {
        return execute(playerUUID, "__legacy", buttonId, operation);
    }

    /** The stable logical identity excludes render/session IDs and menu revisions.
     * A refreshed callback must still contend with an operation from the old render. */
    public CompletableFuture<Boolean> execute(UUID playerUUID, String menuId, String buttonId,
            Supplier<? extends CompletableFuture<?>> operation) {
        Objects.requireNonNull(playerUUID); Objects.requireNonNull(operation);
        if (menuId == null || menuId.isBlank()) throw new IllegalArgumentException("menuId is required");
        if (buttonId == null || buttonId.isBlank()) throw new IllegalArgumentException("buttonId is required");
        Key key = new Key(playerUUID, menuId, buttonId);
        Object owner = new Object();
        if (locks.putIfAbsent(key, owner) != null) return CompletableFuture.completedFuture(false);
        boolean attached = false;
        try {
            CompletableFuture<?> running = Objects.requireNonNull(operation.get(), "operation future is required");
            CompletableFuture<Boolean> settled = running.handle((value, failure) -> {
                try {
                    if (failure != null) throw new CompletionException(failure);
                    return true;
                } finally {
                    locks.remove(key, owner);
                }
            });
            attached = true;
            // Preserve the settlement handler even when the caller cancels its result.
            return settled.copy();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        } finally {
            if (!attached) locks.remove(key, owner);
        }
    }
}
