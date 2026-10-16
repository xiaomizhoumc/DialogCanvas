package dialogcanvas.storage;

import dialogcanvas.model.MenuJson;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JSON-compatible YAML values only: no tags, aliases, duplicate keys or implicit date objects. */
public final class MenuYaml {
    public static final int MAX_BYTES = 1024 * 1024;
    private MenuYaml() {}
    public static JsonNode decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Menu file too large");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(64); options.setCodePointLimit(MAX_BYTES);
        String source;
        try { source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("YAML must be valid UTF-8"); }
        Object value = new Yaml(new SafeConstructor(options)).load(source);
        check(value);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Menu must be an object");
        return MenuJson.createMapper().valueToTree(value);
    }
    private static void check(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> { if (!(key instanceof String)) throw new IllegalArgumentException("Non-string YAML key"); check(child); });
        } else if (value instanceof List<?> list) list.forEach(MenuYaml::check);
        else if (value != null && !(value instanceof String || value instanceof Boolean || value instanceof Number)) {
            throw new IllegalArgumentException("Unsupported YAML scalar");
        }
    }
    public static byte[] encode(JsonNode document) {
        var mapper = MenuJson.createMapper();
        Object value = mapper.readValue(mapper.writeValueAsString(document), Object.class);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setAllowUnicode(true);
        options.setIndent(2); options.setIndicatorIndent(2); options.setIndentWithIndicator(true);
        options.setSplitLines(false); options.setPrettyFlow(true);
        byte[] bytes = new Yaml(options).dump(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Menu file too large");
        return bytes;
    }
}
