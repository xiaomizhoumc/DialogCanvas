package dialogcanvas.web.menu;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import dialogcanvas.model.*;
import dialogcanvas.storage.MenuYaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import java.lang.reflect.*;
import java.time.ZoneId;
import java.util.*;

/** Schema reflects the existing POJOs; capability flags describe deployed code, not planned features. */
public final class MenuMetadataApi {
    private final JsonMapper json = MenuJson.createMapper();
    private final ObjectNode definitions = json.createObjectNode();
    private final Map<Class<?>, String> names = new HashMap<>();
    public JsonNode response(MenuRepository repository) {
        ObjectNode schema = json.createObjectNode(); schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.setAll((ObjectNode) reference(MenuDefinition.class)); schema.set("$defs", definitions);
        var environment = repository.validator().environment();
        return json.valueToTree(Map.of(
                "apiVersion", 1, "menuSchemaVersion", 1,
                "schemas", Map.of("MenuDefinition", schema),
                "capabilities", Map.ofEntries(
                    Map.entry("menuCrud", true), Map.entry("menuServiceReady", repository.ready()),
                    Map.entry("validation", true), Map.entry("gameMenuOpeningConnected", true),
                    Map.entry("passwordChange", true),
                    Map.entry("customActionItems", Map.of("implemented", true, "registry", "server_item_registry", "externalPluginItems", false,
                        "fields", List.of("name", "lore", "enchantments", "attributes", "unbreakable"), "enchantmentLevel", List.of(1, 255),
                        "attributeOperations", List.of("ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1"),
                        "attributeSlots", List.of("any", "mainhand", "offhand", "hand", "feet", "legs", "chest", "head", "armor", "body", "saddle"))),
                    Map.entry("mainMenuShortcut", Map.of("implemented", true, "maxMenus", 1, "gesture", "sneak_swap_hand", "permission", "dcmenu.shortcut")),
                    Map.entry("dialogTypes", List.of("notice", "confirmation", "multi_action")),
                    Map.entry("bodyTypes", List.of("text", "item")),
                    Map.entry("inputTypes", List.of("bool", "text", "number_range", "single_option")),
                    Map.entry("buttonActionTypes", List.of("custom", "close", "none")),
                    Map.entry("textFormats", List.of("plain", "legacy", "minimessage", "component")),
                    Map.entry("economy", Map.of("adapterConnected", repository.validator().productionConnected(), "provider", repository.validator().economyProvider())),
                    Map.entry("serverPause", false),
                    Map.entry("commandExecution", Map.of("implemented", true, "executors", List.of("player", "op", "console"),
                        "playerVariables", List.of("{player}", "{uuid}"), "argumentLayout", "one_per_line", "permissionCheck", "target_command")),
                    Map.entry("conditionEngine", Map.of("implemented", true, "productionConnected", repository.validator().productionConnected(),
                        "types", List.of("permission", "placeholder", "player_data", "numeric", "string"))),
                    Map.entry("actionEngine", Map.of("implemented", true, "productionConnected", repository.validator().productionConnected(),
                        "types", List.of("message", "command", "economy_deposit", "economy_withdraw", "give_item", "take_item", "player_data_set", "player_data_increment", "play_sound", "open_menu", "close_menu", "refresh_menu", "return"))),
                    Map.entry("playerLimit", Map.of("implemented", true, "productionConnected", repository.validator().productionConnected(),
                        "resetModes", List.of("DAILY", "WEEKLY", "MONTHLY", "NEVER", "TIMED"))),
                    Map.entry("serverLimit", false), Map.entry("gamePreview", false), Map.entry("templates", false),
                    Map.entry("history", false), Map.entry("diff", false), Map.entry("webSocket", false), Map.entry("frontendEditor", true)),
                "constraints", Map.ofEntries(
                    Map.entry("idPattern", "[a-z0-9_-]{1,64}"), Map.entry("reservedIds", List.of("validate", "con", "prn", "aux", "nul", "com1..com9", "lpt1..lpt9")),
                    Map.entry("maxRequestBytes", MenuYaml.MAX_BYTES), Map.entry("maxFileBytes", MenuYaml.MAX_BYTES),
                    Map.entry("documentMaxDepth", 64),
                    Map.entry("conditionMaxDepth", 32), Map.entry("conditionMaxNodes", 1024),
                    Map.entry("numericExpressionMaxDepth", 32), Map.entry("numericExpressionMaxLength", 1024),
                    Map.entry("buttonWidth", List.of(1, 1024)), Map.entry("bodyTextWidth", List.of(1, 1024)),
                    Map.entry("bodyItemSize", List.of(1, 256)), Map.entry("multilineHeight", List.of(1, 512)),
                    Map.entry("delayUnit", "tick"), Map.entry("cooldownUnit", "second"), Map.entry("timedResetUnit", "second"),
                    Map.entry("serverTimeZone", ZoneId.systemDefault().getId()), Map.entry("deleteReferencedMenu", "reject"),
                    Map.entry("semanticValidation", "server-authoritative")),
                "defaults", Map.of("textShorthandFormat", "legacy", "buttonActionType", "custom", "chance", 1, "delay", 0,
                    "itemAttributeOperation", "ADD_NUMBER", "itemAttributeSlot", "mainhand"),
                "iconMappings", dialogcanvas.dialog.icon.VanillaIconResolver.mappings(),
                "itemRegistries", Map.of("enchantments", environment.enchantments().stream().sorted().toList(), "attributes", environment.attributes().stream().sorted().toList()),
                "materials", environment.materials().stream().sorted().toList(), "sounds", environment.sounds().stream().sorted().toList()));
    }

    private JsonNode reference(Type type) {
        if (type instanceof ParameterizedType parameter) {
            var result = json.createObjectNode();
            if (parameter.getRawType() == List.class) { result.put("type", "array"); result.set("items", reference(parameter.getActualTypeArguments()[0])); }
            else { result.put("type", "object"); result.set("additionalProperties", reference(parameter.getActualTypeArguments()[1])); }
            return result;
        }
        if (!(type instanceof Class<?> clazz)) return json.createObjectNode();
        if (clazz == JsonNode.class) return json.createObjectNode();
        if (clazz == String.class) return json.valueToTree(Map.of("type", "string"));
        if (clazz == Boolean.class) return json.valueToTree(Map.of("type", "boolean"));
        if (clazz == Integer.class || clazz == Long.class) return json.valueToTree(Map.of("type", "integer"));
        if (clazz == Double.class) return json.valueToTree(Map.of("type", "number"));
        if (clazz.isEnum()) {
            var values = json.createArrayNode();
            for (var value : clazz.getEnumConstants()) {
                if (clazz == DialogType.class && !Set.of(DialogType.NOTICE, DialogType.CONFIRMATION, DialogType.MULTI_ACTION).contains(value)) continue;
                try { var annotation = clazz.getField(((Enum<?>) value).name()).getAnnotation(JsonProperty.class);
                    values.add(annotation == null ? ((Enum<?>) value).name() : annotation.value());
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }
            var result = json.createObjectNode(); result.put("type", "string"); result.set("enum", values); return result;
        }
        String name = names.computeIfAbsent(clazz, key -> key.getName().substring("dialogcanvas.model.".length()).replace('$', '_'));
        if (!definitions.has(name)) {
            ObjectNode schema = json.createObjectNode(); definitions.set(name, schema);
            if (clazz == TextSpec.class) {
                schema.set("anyOf", json.valueToTree(List.of(Map.of("type", "string"), Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("format", "value"), "properties", Map.of("format", Map.of("enum", List.of("plain", "legacy", "minimessage", "component")), "value", Map.of())))));
            } else if (clazz.isAnnotationPresent(JsonSubTypes.class)) {
                var alternatives = json.createArrayNode();
                for (var subtype : clazz.getAnnotation(JsonSubTypes.class).value()) {
                    alternatives.add(reference(subtype.value()));
                    String subtypeName = names.get(subtype.value());
                    ((ObjectNode) definitions.get(subtypeName).get("properties")).set("type", json.valueToTree(Map.of("const", subtype.name())));
                }
                schema.set("oneOf", alternatives);
            } else {
                schema.put("type", "object"); schema.put("additionalProperties", false);
                var properties = json.createObjectNode(); schema.set("properties", properties);
                for (Class<?> base = clazz; base != null && base != ModelPojo.class && base != Object.class; base = base.getSuperclass()) {
                    for (var field : base.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
                        if (clazz == MenuDefinition.class && Set.of("dialogs", "buttonWidth").contains(field.getName())) continue;
                        if (clazz == ActionDefinition.class && Set.of("template", "clickEvent", "additions").contains(field.getName())) continue;
                        if (clazz == BodyBlock.Text.class && Set.of("color", "colorMode").contains(field.getName())) continue;
                        properties.set(field.getName(), json.valueToTree(Map.of("anyOf", List.of(reference(field.getGenericType()), Map.of("type", "null")))));
                    }
                }
                var required = new ArrayList<String>();
                if (clazz == MenuDefinition.class) required.addAll(List.of("schemaVersion", "id", "title", "dialogType"));
                if (clazz == ActionDefinition.class) required.addAll(List.of("id", "label"));
                if (InputDefinition.class.isAssignableFrom(clazz)) required.addAll(List.of("type", "key", "label"));
                if (clazz == InputDefinition.NumberRange.class) required.addAll(List.of("start", "end"));
                if (BodyBlock.class.isAssignableFrom(clazz) || ActionStepDefinition.class.isAssignableFrom(clazz)) required.add("type");
                if (clazz == BodyBlock.Text.class) required.add("text");
                if (clazz == BodyBlock.Item.class) required.add("material");
                schema.set("required", json.valueToTree(required));
                if (clazz == MenuDefinition.class) {
                    properties.set("schemaVersion", json.valueToTree(Map.of("const", 1)));
                    properties.set("id", json.valueToTree(Map.of("type", "string", "pattern", "^[a-z0-9_-]{1,64}$")));
                    properties.set("dialogType", json.valueToTree(Map.of("enum", List.of("notice", "confirmation", "multi_action"))));
                }
                if (clazz == MenuMetadata.class) schema.put("readOnly", true);
                if (clazz == ActionDefinition.class) properties.set("actionType", json.valueToTree(Map.of("enum", List.of("custom", "close", "none"))));
                if (clazz == ActionDefinition.class) properties.set("type", json.valueToTree(Map.of("anyOf", List.of(Map.of("const", "button"), Map.of("type", "null")))));
                if (clazz == BodyBlock.Item.class) {
                    ObjectNode description = ((ObjectNode) definitions.get(names.get(BodyBlock.Text.class))).deepCopy();
                    description.set("required", json.valueToTree(List.of("text")));
                    properties.set("description", json.valueToTree(Map.of("anyOf", List.of(description, Map.of("type", "null")))));
                }
            }
        }
        return json.valueToTree(Map.of("$ref", "#/$defs/" + name));
    }
}
