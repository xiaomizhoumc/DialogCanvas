package dialogcanvas.dialog;

import dialogcanvas.model.MenuJson;
import dialogcanvas.model.TextSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Converts the shared text representation without consulting Bukkit. */
public final class TextRenderer {
    /** Keep text styles independent of the sprite so coloured labels do not tint item textures. */
    public Component renderButtonLabel(TextSpec label, Component icon) {
        Component text = render(label);
        return icon == null ? text : Component.empty().append(icon).append(Component.space()).append(text);
    }

    public Component render(TextSpec text) {
        if (text == null || text.getFormat() == null || text.getValue() == null) {
            throw new IllegalArgumentException("Text requires format and value");
        }
        if (!"component".equals(text.getFormat()) && !text.getValue().isString()) {
            throw new IllegalArgumentException("Text value must be a string");
        }
        return switch (text.getFormat()) {
            case "plain" -> Component.text(text.getValue().asString());
            case "legacy" -> LegacyComponentSerializer.legacyAmpersand().deserialize(text.getValue().asString());
            case "minimessage" -> MiniMessage.miniMessage().deserialize(text.getValue().asString());
            case "component" -> GsonComponentSerializer.gson().deserialize(
                    MenuJson.createMapper().writeValueAsString(text.getValue()));
            default -> throw new IllegalArgumentException("Unsupported text format: " + text.getFormat());
        };
    }
}
