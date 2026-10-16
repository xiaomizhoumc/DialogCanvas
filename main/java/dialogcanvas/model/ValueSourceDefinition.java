package dialogcanvas.model;

import tools.jackson.databind.JsonNode;

/** Exactly one source: literal, input, placeholder or player_data. */
public final class ValueSourceDefinition extends ModelPojo {
    private String source;
    private JsonNode value;
    private String key;
    private String placeholder;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getPlaceholder() { return placeholder; }
    public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
}
