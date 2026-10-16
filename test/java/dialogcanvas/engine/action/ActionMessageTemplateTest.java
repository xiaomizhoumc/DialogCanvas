package dialogcanvas.engine.action;

import dialogcanvas.dialog.TextRenderer;
import dialogcanvas.model.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ActionMessageTemplateTest {
    final tools.jackson.databind.json.JsonMapper mapper = MenuJson.createMapper();
    final Map<String, tools.jackson.databind.JsonNode> inputs = Map.of("amount", mapper.valueToTree(128));
    String plain(TextSpec text) { return PlainTextComponentSerializer.plainText().serialize(new TextRenderer().render(text)); }

    @ParameterizedTest @ValueSource(strings = {"plain", "legacy", "minimessage"})
    void displaysActualQuantityAndTotalWithoutChangingDocument(String format) {
        var original = new TextSpec(); original.setFormat(format);
        original.setValue(mapper.valueToTree("出售橡木原木 ×$(amount)，获得 $(1 * $(amount)) 游戏币。"));
        var before = original.toJson().deepCopy();
        ActionMessageTemplate.validate(original, Set.of("amount"));
        var resolved = ActionMessageTemplate.resolve(original, inputs, token -> fail("No PAPI needed"));
        assertEquals("出售橡木原木 ×128，获得 128 游戏币。", plain(resolved));
        assertEquals(before, original.toJson());
    }
    @Test void fractionalTotalMatchesEconomyExpressionAndHasNoTrailingZeroes() {
        var original = TextSpec.fromJson(mapper.valueToTree("获得 $(5 * $(amount) / 100) 游戏币，数量 $(amount)"));
        assertEquals("获得 6.4 游戏币，数量 128", plain(ActionMessageTemplate.resolve(original, inputs, token -> token)));
    }
    @Test void numericConstantsDoNotRequireInputs() {
        var original = TextSpec.fromJson(mapper.valueToTree("$(1)、$(-1)、$(0.5)"));
        ActionMessageTemplate.validate(original, Set.of());
        assertEquals("1、-1、0.5", plain(ActionMessageTemplate.resolve(original, Map.of(), token -> token)));
    }
    @Test void nestedParenthesesAndGradientWorkWithRealAdventureRenderer() {
        var original = new TextSpec(); original.setFormat("minimessage");
        original.setValue(mapper.valueToTree("<gradient:red:blue><bold>花费 $((1 + 1) * $(amount)) 游戏币</bold></gradient>"));
        assertEquals("花费 256 游戏币", plain(ActionMessageTemplate.resolve(original, inputs, token -> token)));
    }
    @Test void resolvesPapiTokensAndPreservesOrdinaryPercentSigns() {
        var original = TextSpec.fromJson(mapper.valueToTree("%player_name%：$(amount)，进度100%"));
        var called = new ArrayList<String>();
        assertEquals("Buyer：128，进度100%", plain(ActionMessageTemplate.resolve(original, inputs, token -> { called.add(token); return "Buyer"; })));
        assertEquals(List.of("%player_name%"), called);
    }
    @Test void componentTextIsExpandedButClickMetadataRemainsUnchanged() {
        var original = TextSpec.fromJson(mapper.readTree("""
            {"format":"component","value":{"text":"金额 $(amount)","extra":[{"text":"；总计 $(2 * $(amount))"}],"clickEvent":{"action":"run_command","value":"/say $(amount)"}}}
            """));
        var resolved = ActionMessageTemplate.resolve(original, inputs, token -> token);
        assertEquals("金额 128；总计 256", plain(resolved));
        assertEquals("/say $(amount)", resolved.getValue().get("clickEvent").get("value").asString());
    }
    @ParameterizedTest @ValueSource(strings = {"$(missing)", "$(2 * $(missing))", "$(amount", "$(java.lang.Runtime())"})
    void rejectsUnknownInputsAndInvalidGrammar(String source) {
        var text = TextSpec.fromJson(mapper.valueToTree(source));
        assertThrows(IllegalArgumentException.class, () -> ActionMessageTemplate.validate(text, Set.of("amount")));
    }
}
