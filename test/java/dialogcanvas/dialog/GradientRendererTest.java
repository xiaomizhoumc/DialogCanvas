package dialogcanvas.dialog;

import dialogcanvas.model.MenuJson;
import dialogcanvas.model.TextSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** The exact native parser fixtures consumed by the browser interpolation tests. */
class GradientRendererTest {
    @Test
    void verifiesNativeMulticolourUnicodeWhitespaceAndStyleNodeFixtures() throws Exception {
        var mapper = MenuJson.createMapper();
        var fixtures = mapper.readTree(Files.readString(Path.of("../frontend/src/components/__fixtures__/gradient-adventure-4.26.1.json")));
        assertEquals("4.26.1", fixtures.get("adventureVersion").asString());
        for (var fixture : fixtures.get("cases")) {
            var spec = new TextSpec(); spec.setFormat("minimessage"); spec.setValue(fixture.get("source"));
            var colors = new ArrayList<String>(); var text = new StringBuilder();
            collect(new TextRenderer().render(spec), null, colors, text);
            var expected = new ArrayList<String>();
            fixture.get("colors").forEach(value -> expected.add(value.asString()));
            assertEquals(fixture.get("text").asString(), text.toString(), fixture.get("source").asString());
            assertEquals(expected, colors, fixture.get("source").asString());
        }
    }

    private void collect(Component component, TextColor inherited, List<String> colors, StringBuilder text) {
        TextColor color = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent value) {
            value.content().codePoints().forEach(codePoint -> {
                text.appendCodePoint(codePoint);
                assertNotNull(color);
                colors.add(color.asHexString().toUpperCase(Locale.ROOT));
            });
        }
        component.children().forEach(child -> collect(child, color, colors, text));
    }
}
