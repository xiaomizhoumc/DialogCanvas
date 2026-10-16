package dialogcanvas.engine.condition;

import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Read an already loaded snapshot. Implementations must not perform disk I/O during evaluation. */
@FunctionalInterface
public interface PlayerDataView {
    Optional<JsonNode> find(UUID playerId, String key);
}
