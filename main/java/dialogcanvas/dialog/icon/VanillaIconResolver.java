package dialogcanvas.dialog.icon;

import dialogcanvas.model.ButtonIconDefinition;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.ObjectContents;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import dialogcanvas.model.MenuJson;
import java.io.IOException;

/** Catalog generated from fixed-version item models and atlas definitions; no textures are bundled. */
public final class VanillaIconResolver {
    public record SpriteMapping(String atlas, String sprite, String category, String note) { }
    public record Result(Component icon, String error) {
        public boolean success() { return icon != null; }
    }
    private static final Map<String, SpriteMapping> MAPPINGS = loadMappings();
    private static Map<String, SpriteMapping> loadMappings() {
        try (var input = VanillaIconResolver.class.getResourceAsStream("/vanilla-icons.json")) {
            if (input == null) throw new IllegalStateException("Missing verified vanilla icon catalog");
            var document = MenuJson.createMapper().readTree(input.readAllBytes());
            var mappings = new TreeMap<String, SpriteMapping>();
            document.properties().forEach(entry -> {
                var value = entry.getValue();
                mappings.put(entry.getKey(), new SpriteMapping(value.get("atlas").asString(), value.get("sprite").asString(),
                        value.get("category").asString(), value.get("note").asString()));
            });
            return Collections.unmodifiableMap(mappings);
        } catch (IOException failure) { throw new IllegalStateException("Cannot read verified vanilla icon catalog", failure); }
    }
    public static Map<String, SpriteMapping> mappings() { return MAPPINGS; }

    public Result resolve(ButtonIconDefinition definition) {
        if (definition == null) return new Result(null, null);
        if (!"item".equals(definition.getType())) return new Result(null, "UNSUPPORTED_ICON_TYPE: only item is supported");
        String material = definition.getMaterial();
        if (material == null || material.isBlank()) return new Result(null, "MISSING_ICON_MATERIAL");
        String key = material.trim();
        if (key.indexOf(':') >= 0) {
            if (!key.regionMatches(true, 0, "minecraft:", 0, 10)) return new Result(null, "UNSUPPORTED_ICON_NAMESPACE");
            key = key.substring(10);
        }
        SpriteMapping mapping = MAPPINGS.get(key.toUpperCase(Locale.ROOT));
        if (mapping == null) return new Result(null, "UNMAPPED_ICON_MATERIAL: no verified flat vanilla texture; check the item registry name and supported Sprite mappings");
        Component icon = Component.object(ObjectContents.sprite(Key.key(mapping.atlas()), Key.key(mapping.sprite())))
                .color(NamedTextColor.WHITE)
                .shadowColor(ShadowColor.none());
        return new Result(icon, null);
    }
}
