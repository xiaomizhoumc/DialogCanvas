package dialogcanvas.engine.action;

import java.util.concurrent.CompletableFuture;

/** Per-transaction instance. Side effects explicitly refuse rollback once executed. */
public interface ExecutableAction {
    CompletableFuture<Void> execute();
    CompletableFuture<Void> rollback();
    Object recoveryData();
}
