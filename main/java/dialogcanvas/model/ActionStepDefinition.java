package dialogcanvas.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Action definitions: delay is an integer tick count; chance defaults to one. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionStepDefinition.Command.class, name = "command"),
        @JsonSubTypes.Type(value = ActionStepDefinition.EconomyDeposit.class, name = "economy_deposit"),
        @JsonSubTypes.Type(value = ActionStepDefinition.TakeItem.class, name = "take_item"),
        @JsonSubTypes.Type(value = ActionStepDefinition.PlayerDataSet.class, name = "player_data_set"),
        @JsonSubTypes.Type(value = ActionStepDefinition.OpenMenu.class, name = "open_menu"),
        @JsonSubTypes.Type(value = ActionStepDefinition.CloseMenu.class, name = "close_menu"),
        @JsonSubTypes.Type(value = ActionStepDefinition.RefreshMenu.class, name = "refresh_menu"),
        @JsonSubTypes.Type(value = ActionStepDefinition.EconomyWithdraw.class, name = "economy_withdraw"),
        @JsonSubTypes.Type(value = ActionStepDefinition.PlayerDataIncrement.class, name = "player_data_increment"),
        @JsonSubTypes.Type(value = ActionStepDefinition.GiveItem.class, name = "give_item"),
        @JsonSubTypes.Type(value = ActionStepDefinition.Message.class, name = "message"),
        @JsonSubTypes.Type(value = ActionStepDefinition.PlaySound.class, name = "play_sound"),
        @JsonSubTypes.Type(value = ActionStepDefinition.Return.class, name = "return")
})
public abstract class ActionStepDefinition extends ModelPojo {
    public static final class Command extends ActionStepDefinition {
        public Command() { setType("command"); }
        private String command;
        public String getCommand() { return command; }
        public void setCommand(String value) { command = value; }

        private List<String> arguments;
        public List<String> getArguments() { return arguments; }
        public void setArguments(List<String> value) { arguments = value; }

        private String executor;
        public String getExecutor() { return executor; }
        public void setExecutor(String value) { executor = value; }
    }

    public static final class EconomyDeposit extends ActionStepDefinition {
        public EconomyDeposit() { setType("economy_deposit"); }
        private String amount;
        public String getAmount() { return amount; }
        public void setAmount(String value) { amount = value; }
    }

    public static final class TakeItem extends ItemActionDefinition {
        public TakeItem() { setType("take_item"); }
    }

    public static final class PlayerDataSet extends ActionStepDefinition {
        public PlayerDataSet() { setType("player_data_set"); }
        private String key;
        public String getKey() { return key; }
        public void setKey(String value) { key = value; }

        private tools.jackson.databind.JsonNode value;
        public tools.jackson.databind.JsonNode getValue() { return value; }
        public void setValue(tools.jackson.databind.JsonNode value) { value = value; }
    }

    public static final class OpenMenu extends ActionStepDefinition {
        public OpenMenu() { setType("open_menu"); }
        private String menuId;
        public String getMenuId() { return menuId; }
        public void setMenuId(String value) { menuId = value; }
    }

    public static final class CloseMenu extends ActionStepDefinition {
        public CloseMenu() { setType("close_menu"); }

    }

    public static final class RefreshMenu extends ActionStepDefinition {
        public RefreshMenu() { setType("refresh_menu"); }

    }

    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private Double delay;

    public Double getDelay() {
        return delay;
    }

    public void setDelay(Double delay) {
        this.delay = delay;
    }

    private Double chance;

    public Double getChance() {
        return chance;
    }

    public void setChance(Double chance) {
        this.chance = chance;
    }

    public static final class EconomyWithdraw extends ActionStepDefinition {
        public EconomyWithdraw() {
            setType("economy_withdraw");
        }

        private String amount;

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }
    }

    public static final class PlayerDataIncrement extends ActionStepDefinition {
        public PlayerDataIncrement() {
            setType("player_data_increment");
        }

        private String key;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        private String amount;

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }
    }

    public static final class GiveItem extends ItemActionDefinition {
        public GiveItem() { setType("give_item"); }
    }

    public static final class Message extends ActionStepDefinition {
        public Message() {
            setType("message");
        }

        private TextSpec text;

        public TextSpec getText() {
            return text;
        }

        public void setText(TextSpec text) {
            this.text = text;
        }
    }

    public static final class PlaySound extends ActionStepDefinition {
        public PlaySound() {
            setType("play_sound");
        }

        private String sound;

        public String getSound() {
            return sound;
        }

        public void setSound(String sound) {
            this.sound = sound;
        }

        private Double volume;

        public Double getVolume() {
            return volume;
        }

        public void setVolume(Double volume) {
            this.volume = volume;
        }

        private Double pitch;

        public Double getPitch() {
            return pitch;
        }

        public void setPitch(Double pitch) {
            this.pitch = pitch;
        }
    }

    public static final class Return extends ActionStepDefinition {
        public Return() {
            setType("return");
        }

    }
}
