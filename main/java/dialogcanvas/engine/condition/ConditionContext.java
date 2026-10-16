package dialogcanvas.engine.condition;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.entity.Player;
import tools.jackson.databind.JsonNode;

/** Input values must already have passed Dialog response validation. */
public final class ConditionContext {
    private final Player player;
    private final Map<String, JsonNode> inputs;
    private final PlaceholderResolver placeholders;
    private final PlayerDataView playerData;

    public ConditionContext(Player player, Map<String, JsonNode> inputs,
                            PlaceholderResolver placeholders, PlayerDataView playerData) {
        this.player = Objects.requireNonNull(player);
        var copy = new HashMap<String, JsonNode>();
        Objects.requireNonNull(inputs).forEach((key, value) ->
                copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value).deepCopy()));
        this.inputs = Map.copyOf(copy);
        this.placeholders = Objects.requireNonNull(placeholders);
        this.playerData = Objects.requireNonNull(playerData);
    }

    public Player player() { return player; }
    public PlaceholderResolver placeholders() { return placeholders; }
    public PlayerDataView playerData() { return playerData; }
    JsonNode input(String key) { return inputs.get(key); }
}
