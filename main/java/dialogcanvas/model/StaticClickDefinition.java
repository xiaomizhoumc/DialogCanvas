package dialogcanvas.model;

import tools.jackson.databind.JsonNode;

/** Static click-event payload; supported actions are checked by the converter. */
public final class StaticClickDefinition extends ModelPojo {
    private String action;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    private JsonNode value;

    public JsonNode getValue() {
        return value;
    }

    public void setValue(JsonNode value) {
        this.value = value;
    }
}
