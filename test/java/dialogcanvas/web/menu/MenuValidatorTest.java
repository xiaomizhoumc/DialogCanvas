package dialogcanvas.web.menu;

import dialogcanvas.model.MenuJson;
import dialogcanvas.dialog.DialogBehavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MenuValidatorTest {
    private final MenuValidator validator = MenuRepositoryTest.validator();
    @Test void messageInputErrorsPointToTextField() {
        var menu = (ObjectNode) MenuRepositoryTest.menu("welcome");
        var button = menu.putObject("noticeAction"); button.put("id", "test"); button.put("label", "测试");
        var message = button.putArray("actions").addObject(); message.put("type", "message"); message.put("text", "金额 $(1 * $(missing))");
        var failure = assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of()));
        assertEquals("/menu/noticeAction/actions/0/text", failure.details().getFirst().path());
        assertEquals("INVALID_EXPRESSION", failure.details().getFirst().code());
    }
    @ParameterizedTest @ValueSource(strings = {"player", "op", "console"})
    void acceptsThreeExecutorsAndLiteralMinecraftArguments(String executor) {
        var menu = (ObjectNode) MenuRepositoryTest.menu("welcome");
        var button = menu.putObject("noticeAction"); button.put("id", "test"); button.put("label", "测试");
        var action = button.putArray("actions").addObject(); action.put("type", "command");
        action.put("executor", executor); action.put("command", "minecraft:tellraw");
        action.putArray("arguments").add("@s").add("{\"text\":\"中文 内容\"}");
        assertDoesNotThrow(() -> validator.prepare(menu, Set.of()));
    }
    @ParameterizedTest
    @CsvSource({"true,NONE,true", "false,NONE,true", "null,NONE,true", "missing,NONE,true",
            "true,CLOSE,true", "false,CLOSE,true", "null,CLOSE,true", "missing,CLOSE,true",
            "true,WAIT_FOR_RESPONSE,true", "false,WAIT_FOR_RESPONSE,true", "null,WAIT_FOR_RESPONSE,true", "missing,WAIT_FOR_RESPONSE,true",
            "true,missing,true", "false,missing,true", "null,missing,true", "missing,missing,true"})
    void dialogPauseAndAfterActionFollowPaperConstraints(String pause, String afterAction, boolean valid) {
        var menu = (ObjectNode) MenuRepositoryTest.menu("welcome");
        if (pause.equals("null")) menu.putNull("pause");
        else if (!pause.equals("missing")) menu.put("pause", Boolean.parseBoolean(pause));
        if (!afterAction.equals("missing")) menu.put("afterAction", afterAction);
        if (!valid) {
            var failure = assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of()));
            assertEquals(422, failure.status());
            assertEquals("/menu/afterAction", failure.details().getFirst().path());
            assertEquals("INCOMPATIBLE_DIALOG_BEHAVIOR", failure.details().getFirst().code());
        } else {
            var prepared = validator.prepare(menu, Set.of());
            assertEquals(pause.equals("true"), prepared.warnings().stream().anyMatch(w -> w.code().equals("SERVER_PAUSE_IGNORED")));
            assertEquals(pause.equals("true") ? Boolean.TRUE : pause.equals("false") ? Boolean.FALSE : null, prepared.menu().getPause());
            assertFalse(DialogBehavior.effectivePause(prepared.menu()));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"../escape", "UpperCase", "con", "com1", "validate", "", "a/b", "a.b"})
    void rejectsUnsafeIds(String id) {
        var menu = (ObjectNode) MenuRepositoryTest.menu("welcome"); menu.put("id", id);
        var failure = assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of()));
        assertEquals("/menu/id", failure.details().getFirst().path());
    }
    @ParameterizedTest @ValueSource(strings = {
        "\"extra\":true", "\"columns\":1.5", "\"canCloseWithEscape\":\"true\"", "\"dialogType\":\"dialog_list\"",
        "\"body\":[{\"type\":\"item\",\"material\":\"UNKNOWN\"}]",
        "\"inputs\":[{\"type\":\"number_range\",\"key\":\"n\",\"label\":\"N\",\"start\":1,\"end\":3,\"step\":0}]",
        "\"inputs\":[{\"type\":\"bool\",\"key\":\"n\",\"label\":\"N\"},{\"type\":\"bool\",\"key\":\"n\",\"label\":\"N\"}]",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"actionType\":\"command_template\"}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"conditions\":{\"operator\":\"XOR\",\"items\":[]}}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"actions\":[{\"type\":\"command\",\"command\":\"say\",\"arguments\":[\"hello\\nworld\"]}]}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"actions\":[{\"type\":\"economy_withdraw\",\"amount\":\"1/0\"}]}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"actions\":[{\"type\":\"give_item\",\"material\":\"STONE\",\"amount\":\"$(missing)\"}]}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"actions\":[{\"type\":\"message\",\"text\":\"Hi\",\"delay\":0.5}]}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"limit\":{\"buy\":{\"player\":3,\"reset\":\"TIMED\"}}}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"limit\":{\"buy\":{\"server\":3,\"reset\":\"DAILY\"}}}",
        "\"noticeAction\":{\"id\":\"go\",\"label\":\"Go\",\"cooldown\":{\"buy\":0.5}}"
    })
    void invalidModelAndBusinessFieldsHaveLocatableErrors(String fields) {
        var menu = (ObjectNode) MenuRepositoryTest.menu("welcome");
        var extra = MenuJson.createMapper().readTree("{" + fields + "}"); extra.properties().forEach(entry -> menu.set(entry.getKey(), entry.getValue()));
        var failure = assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of()));
        assertEquals(422, failure.status()); assertTrue(failure.details().getFirst().path().startsWith("/menu"));
    }
    @Test void validDynamicExpressionsAndSelfReferenceAreAcceptedWithWarnings() {
        var document = MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"self\",\"title\":\"Self\",\"dialogType\":\"notice\",\"inputs\":[{\"type\":\"number_range\",\"key\":\"amount\",\"label\":\"Amount\",\"start\":1,\"end\":3}],\"noticeAction\":{\"id\":\"buy\",\"label\":\"Buy\",\"icon\":{\"material\":\"BAD\"},\"actions\":[{\"type\":\"economy_withdraw\",\"amount\":\"1000 * $(amount)\"},{\"type\":\"open_menu\",\"menuId\":\"self\"}]}}");
        var prepared = validator.prepare(document, Set.of());
        assertEquals(Set.of("self"), prepared.references());
        assertEquals(Set.of("ICON_UNMAPPED", "BUSINESS_NOT_CONNECTED"), prepared.warnings().stream().map(MenuRegistry.Warning::code).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void allBundledExamplesPassTheSharedValidator() throws Exception {
        for (String file : java.util.List.of("minimal-menu.json", "icon-buttons.json", "shop-demo.json", "menu-demo.json")) {
            try (var resource = getClass().getResourceAsStream("/examples/" + file)) {
                var node = MenuJson.createMapper().readTree(resource.readAllBytes());
                assertNotNull(validator.prepare(node, Set.of()));
            }
        }
    }
}
