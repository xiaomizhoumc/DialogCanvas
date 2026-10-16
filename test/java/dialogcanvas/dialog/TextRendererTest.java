package dialogcanvas.dialog;

import dialogcanvas.model.MenuJson;
import dialogcanvas.model.TextSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.ShadowColor;
import dialogcanvas.dialog.icon.VanillaIconResolver;
import dialogcanvas.model.ButtonIconDefinition;
import net.kyori.adventure.text.ObjectComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextRendererTest {
    private final TextRenderer renderer = new TextRenderer();

    private TextSpec text(String json) {
        return MenuJson.createMapper().readValue(json, TextSpec.class);
    }

    private Component renderValue(String format, String value) {
        var spec = new TextSpec();
        spec.setFormat(format);
        spec.setValue(MenuJson.createMapper().valueToTree(value));
        return renderer.render(spec);
    }

    @Test
    void verifiesFixedBackendLegacyHexAndColourResetContract() {
        assertEquals(Component.text("A", TextColor.color(0x123456)), renderValue("legacy", "&#123456A"));
        assertEquals(Component.text("A", TextColor.color(0xABC123)), renderValue("legacy", "&x&A&B&C&1&2&3A"));
        assertEquals(Component.text("A").decorate(TextDecoration.BOLD), renderValue("legacy", "&LA"));
        assertEquals(Component.text("A", NamedTextColor.RED), renderValue("legacy", "&CA"));
        assertEquals(Component.text("§cA"), renderValue("legacy", "§cA"));
        assertEquals(Component.empty().append(Component.text("A").decorate(TextDecoration.BOLD))
            .append(Component.text("B", NamedTextColor.RED)).append(Component.text("C")), renderValue("legacy", "&lA&cB&rC"));
        assertEquals(Component.text("&XA"), renderValue("legacy", "&XA"));
    }

    @Test
    void verifiesMiniMessageScopesResetAliasesAndLiteralEscapes() {
        Component nested = renderValue("minimessage", "<bold>A<red>B</red>C</bold>D");
        assertEquals(TextDecoration.State.TRUE, nested.children().getFirst().decoration(TextDecoration.BOLD));
        assertEquals(NamedTextColor.RED, nested.children().getFirst().children().getFirst().color());
        assertEquals(Component.empty().append(Component.text("A").decorate(TextDecoration.BOLD)).append(Component.text("B")), renderValue("minimessage", "<bold>A<reset>B"));
        assertEquals(Component.text("ABC").decorate(TextDecoration.BOLD), renderValue("minimessage", "<bold>A<bold>B</bold>C</bold>"));
        assertEquals(Component.text("A").decorate(TextDecoration.BOLD), renderValue("minimessage", "<BOLD>A"));
        assertEquals(Component.text("A").decorate(TextDecoration.STRIKETHROUGH), renderValue("minimessage", "<st>A</st>"));
        assertEquals(Component.text("A", NamedTextColor.RED), renderValue("minimessage", "<c:red>A</c>"));
        assertEquals(Component.text("A").decorate(TextDecoration.ITALIC), renderValue("minimessage", "<em>A</em>"));
        assertEquals("a\\q \\> <red> \\ b", PlainTextComponentSerializer.plainText().serialize(renderValue("minimessage", "a\\q \\> \\<red> \\\\ b")));
        assertEquals("\nA\nB", PlainTextComponentSerializer.plainText().serialize(renderValue("minimessage", "<newline/>A<br/>B")));
    }

    @Test
    void rendersPlainLegacyAndMiniMessage() {
        assertEquals(Component.text("&6title"), renderer.render(text("{\"format\":\"plain\",\"value\":\"&6title\"}")));
        assertEquals(Component.text("title", NamedTextColor.GOLD), renderer.render(text("\"&6title\"")));
        assertEquals(Component.text("title", NamedTextColor.RED), renderer.render(text("{\"format\":\"minimessage\",\"value\":\"<red>title</red>\"}")));
    }

    @Test
    void preservesTranslatableRawComponent() {
        assertEquals(Component.translatable("gui.done"), renderer.render(text("{\"format\":\"component\",\"value\":{\"translate\":\"gui.done\"}}")));
    }

    @Test
    void rejectsIncompleteProgrammaticText() {
        assertThrows(IllegalArgumentException.class, () -> renderer.render(new TextSpec()));
    }

    @Test
    void iconSpaceThenChineseTextPreservesIndependentStyles() {
        var definition = new ButtonIconDefinition(); definition.setMaterial("BONE");
        Component icon = new VanillaIconResolver().resolve(definition).icon();
        var label = text("{\"format\":\"minimessage\",\"value\":\"<red><bold>骨头</bold></red>\"}");
        Component result = renderer.renderButtonLabel(label, icon);
        assertEquals(3, result.children().size());
        assertInstanceOf(ObjectComponent.class, result.children().getFirst());
        assertEquals(NamedTextColor.WHITE, result.children().getFirst().color());
        assertEquals(ShadowColor.none(), result.children().getFirst().shadowColor());
        assertNull(result.shadowColor());
        assertNull(result.children().get(2).shadowColor());
        assertEquals(Component.space(), result.children().get(1));
        assertEquals(renderer.render(label), result.children().get(2));
        assertEquals(renderer.render(label), renderer.renderButtonLabel(label, null));
    }

    @Test
    void keepsExistingMiniMessageSpriteAndComponentConfiguration() {
        var label = text("{\"format\":\"minimessage\",\"value\":\"<sprite:'minecraft:items':'minecraft:item/bone'> 骨头\"}");
        String serialized = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(renderer.renderButtonLabel(label, null));
        assertTrue(serialized.contains("minecraft:item/bone"));
        assertTrue(serialized.contains("骨头"));
        var componentLabel = text("{\"format\":\"component\",\"value\":{\"translate\":\"gui.done\"}}");
        assertEquals(Component.translatable("gui.done"), renderer.renderButtonLabel(componentLabel, null));
    }
}
