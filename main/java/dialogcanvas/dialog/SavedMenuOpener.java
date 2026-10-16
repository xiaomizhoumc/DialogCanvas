package dialogcanvas.dialog;

import dialogcanvas.model.MenuDefinition;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.web.menu.ApiException;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Opens the repository's committed snapshot; never reads a draft or manual YAML. */
public final class SavedMenuOpener {
    private final Scheduler scheduler;
    private final Function<String, CompletableFuture<MenuDefinition>> definitions;
    private final BiFunction<Player, MenuDefinition, CompletableFuture<Void>> display;
    private final Logger logger;

    public SavedMenuOpener(Scheduler scheduler,
                           Function<String, CompletableFuture<MenuDefinition>> definitions,
                           BiFunction<Player, MenuDefinition, CompletableFuture<Void>> display, Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.definitions = Objects.requireNonNull(definitions);
        this.display = Objects.requireNonNull(display);
        this.logger = Objects.requireNonNull(logger);
    }

    public CompletableFuture<Void> open(Player player, String id) {
        CompletableFuture<MenuDefinition> loaded;
        try { loaded = definitions.apply(id); }
        catch (RuntimeException failure) { loaded = CompletableFuture.failedFuture(failure); }
        return loaded.thenCompose(menu -> {
            var shown = new AtomicReference<>(CompletableFuture.<Void>completedFuture(null));
            return scheduler.sync(() -> {
                if (!player.isOnline()) throw new java.util.concurrent.CancellationException("Player disconnected before display");
                shown.set(Objects.requireNonNull(display.apply(player, menu)));
            }).thenCompose(ignored -> shown.get());
        }).exceptionallyCompose(failure -> scheduler.sync(() -> {
            if (!player.isOnline()) return;
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof ApiException api) {
                logger.warning(dialogcanvas.feedback.ErrorFeedback.admin(api.code()));
                player.sendMessage("[DCMenu] " + dialogcanvas.feedback.ErrorFeedback.player(api.code()));
            } else {
                logger.warning(dialogcanvas.feedback.ErrorFeedback.admin("MENU_DISPLAY_FAILED"));
                player.sendMessage("[DCMenu] " + dialogcanvas.feedback.ErrorFeedback.player("MENU_DISPLAY_FAILED"));
            }
        }).thenCompose(ignored -> CompletableFuture.failedFuture(failure)));
    }
}
