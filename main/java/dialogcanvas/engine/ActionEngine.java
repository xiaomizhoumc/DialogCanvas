package dialogcanvas.engine;

import dialogcanvas.model.ActionDefinition;
import dialogcanvas.model.MenuDefinition;
import io.papermc.paper.dialog.DialogResponseView;
import org.bukkit.entity.Player;

/** Invoked on the server main thread. Responses are untrusted and need validation. */
@FunctionalInterface
public interface ActionEngine {
    void execute(Player player, MenuDefinition menu, ActionDefinition button, DialogResponseView response);

    /** Legacy synchronous callbacks remain supported; asynchronous implementations must return
     * a future covering ALL work, including compensation and durable settlement. Caller cancellation
     * must not complete that future while detached business work is still running (return a copy). */
    default java.util.concurrent.CompletableFuture<Void> executeAsync(
            Player player, MenuDefinition menu, ActionDefinition button, DialogResponseView response) {
        try {
            execute(player, menu, button, response);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }
}
