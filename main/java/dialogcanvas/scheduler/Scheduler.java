package dialogcanvas.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/** The entry point for player and other game operations from HTTP threads. */
public final class Scheduler implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Object lock = new Object();
    private final Map<CompletableFuture<Void>, BukkitTask> pending = new HashMap<>();
    private boolean closed;

    public Scheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    public CompletableFuture<Void> sync(Runnable action) {
        return syncAfter(0, action);
    }

    public CompletableFuture<Void> syncAfter(long ticks, Runnable action) {
        if (ticks < 0) throw new IllegalArgumentException("ticks must be nonnegative");
        Objects.requireNonNull(action);
        CompletableFuture<Void> future = new CompletableFuture<>();
        boolean inline = ticks == 0 && Bukkit.isPrimaryThread();
        synchronized (lock) {
            // onDisable runs after Paper marks the plugin disabled. Permit immediate
            // main-thread cleanup until close(), but never register a disabled task.
            if (closed || (!inline && !plugin.isEnabled())) {
                future.completeExceptionally(new RejectedExecutionException("Plugin is stopping"));
                return future;
            }
            if (!inline) {
                try {
                    Runnable scheduled = () -> {
                        synchronized (lock) {
                            pending.remove(future);
                            if (closed) {
                                future.cancel(false);
                                return;
                            }
                        }
                        execute(action, future);
                    };
                    BukkitTask task = ticks == 0 ? Bukkit.getScheduler().runTask(plugin, scheduled)
                            : Bukkit.getScheduler().runTaskLater(plugin, scheduled, ticks);
                    pending.put(future, task);
                    future.whenComplete((ignored, failure) -> {
                        if (future.isCancelled()) {
                            synchronized (lock) { pending.remove(future); }
                            task.cancel();
                        }
                    });
                } catch (RuntimeException failure) {
                    future.completeExceptionally(failure);
                }
            }
        }
        // User code and inline Future continuations must not hold the scheduler lock.
        if (inline) execute(action, future);
        return future;
    }

    @Override
    public void close() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Scheduler.close() must run on the server main thread");
        }
        Map<CompletableFuture<Void>, BukkitTask> tasksToCancel;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            tasksToCancel = new HashMap<>(pending);
            pending.clear();
        }
        // Completion callbacks may re-enter this scheduler; run them outside its lock.
        for (Map.Entry<CompletableFuture<Void>, BukkitTask> entry : tasksToCancel.entrySet()) {
            entry.getValue().cancel();
            entry.getKey().cancel(false);
        }
    }

    private static void execute(Runnable action, CompletableFuture<Void> future) {
        if (future.isCancelled()) {
            return;
        }
        try {
            action.run();
            future.complete(null);
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
