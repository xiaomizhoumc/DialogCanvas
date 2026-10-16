package dialogcanvas.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Uniform text supporting a legacy string shorthand and a format/value object. */
public final class TextSpec {
    private String format;
    private JsonNode value;
    private boolean shorthand;

    public TextSpec() {
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TextSpec fromJson(JsonNode node) {
        TextSpec text = new TextSpec();
        if (node.isString()) {
            text.format = "legacy";
            text.value = node.deepCopy();
            text.shorthand = true;
        } else if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (!entry.getKey().equals("format") && !entry.getKey().equals("value")) {
                    throw new IllegalArgumentException("Unknown TextSpec field: " + entry.getKey());
                }
            });
            JsonNode formatNode = node.get("format");
            if (formatNode == null || !formatNode.isString()) {
                throw new IllegalArgumentException("TextSpec.format must be a string");
            }
            text.format = formatNode.asString();
            text.value = node.get("value");
            if (text.value == null || text.value.isNull()) {
                throw new IllegalArgumentException("TextSpec.value is required");
            }
            text.value = text.value.deepCopy();
            if (!text.format.equals("component") && !text.value.isString()) {
                throw new IllegalArgumentException("Non-component text value must be a string");
            }
            if (!java.util.Set.of("plain", "legacy", "minimessage", "component").contains(text.format)) {
                throw new IllegalArgumentException("Unknown TextSpec format: " + text.format);
            }
        } else {
            throw new IllegalArgumentException("TextSpec must be a string or an object");
        }
        return text;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
        this.shorthand = false;
    }

    public JsonNode getValue() {
        return value;
    }

    public void setValue(JsonNode value) {
        this.value = value;
        this.shorthand = false;
    }

    @JsonValue
    public JsonNode toJson() {
        if (shorthand) {
            return value;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("format", format);
        node.set("value", value);
        return node;
    }
}
