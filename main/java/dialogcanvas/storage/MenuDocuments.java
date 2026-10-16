package dialogcanvas.storage;

import dialogcanvas.model.MenuJson;
import tools.jackson.databind.JsonNode;

/** Formal JSON documents use the same strict mapper and size limit as the API. */
public final class MenuDocuments {
    private MenuDocuments() {}
    public static JsonNode decode(byte[] bytes) {
        if (bytes.length > MenuYaml.MAX_BYTES) throw new IllegalArgumentException("Menu too large");
        JsonNode node = MenuJson.createMapper().readTree(bytes);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Menu must be an object");
        return node;
    }
    public static byte[] encode(JsonNode node) {
        byte[] bytes = MenuJson.createMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        if (bytes.length > MenuYaml.MAX_BYTES) throw new IllegalArgumentException("Menu too large");
        return bytes;
    }
}
