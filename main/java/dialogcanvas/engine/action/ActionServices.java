package dialogcanvas.engine.action;

import dialogcanvas.model.MenuDefinition;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import tools.jackson.databind.JsonNode;

/** Provider calls start on the main thread. Futures may finish elsewhere; no blocking disk I/O. */
public record ActionServices(Economy economy, PlayerData playerData, Menus menus,
                             RecoveryStore recovery, Conditions conditions) {
    public ActionServices {
        java.util.Objects.requireNonNull(economy);
        java.util.Objects.requireNonNull(playerData);
        java.util.Objects.requireNonNull(menus);
        java.util.Objects.requireNonNull(recovery);
        java.util.Objects.requireNonNull(conditions);
    }

    /** Mutations are atomic: failure means no change; successful calls return an exact compensation receipt. */
    public interface Change {
        CompletableFuture<Void> rollback();
        Object recoveryData();
    }

    public interface Economy {
        CompletableFuture<Change> deposit(UUID playerId, double amount);
        CompletableFuture<Change> withdraw(UUID playerId, double amount);
    }

    public interface PlayerData {
        CompletableFuture<Change> set(UUID playerId, String key, JsonNode value);
        CompletableFuture<Change> increment(UUID playerId, String key, double amount);
    }

    public interface Menus {
        CompletableFuture<Void> open(Player player, String menuId);
        CompletableFuture<Void> refresh(Player player, MenuDefinition definition);
        default CompletableFuture<Void> close(Player player) {
            player.closeDialog();
            return CompletableFuture.completedFuture(null);
        }
    }

    @FunctionalInterface
    public interface Conditions {
        CompletableFuture<dialogcanvas.engine.condition.ConditionResult> evaluate(
                Player player, MenuDefinition menu, dialogcanvas.model.ActionDefinition button,
                java.util.Map<String, JsonNode> validatedInputs);
    }

    /** Implementations must durably save before completing in production; tests may use memory. */
    @FunctionalInterface
    public interface RecoveryStore {
        CompletableFuture<Void> save(RecoveryRecord record);
    }

    public record RecoveryRecord(UUID transactionId, UUID playerId, String menuId,
                                 String executionFailure, List<FailedCompensation> failures) {}
    public record FailedCompensation(int actionIndex, String actionType, Object recoveryData, String failure) {}
}
