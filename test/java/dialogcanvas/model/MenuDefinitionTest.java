package dialogcanvas.model;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class MenuDefinitionTest {
    private final JsonMapper mapper = MenuJson.createMapper();

    @Test
    void readsShopWithTypedInputsAndNestedBusinessRules() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/menu-shop.json")) {
            assertNotNull(input);
            MenuDefinition menu = mapper.readValue(input, MenuDefinition.class);
            assertEquals(DialogType.MULTI_ACTION, menu.getDialogType());
            assertEquals("&6武器商店", menu.getTitle().getValue().asString());
            assertEquals("legacy", menu.getTitle().getFormat());
            BodyBlock.Item item = assertInstanceOf(BodyBlock.Item.class, menu.getBody().get(1));
            assertEquals("传说之剑展示", item.getDescription().getText().getValue().asString());
            assertEquals(Boolean.TRUE, item.getGlow());
            assertEquals(1.0, assertInstanceOf(InputDefinition.NumberRange.class,
                    menu.getInputs().get(0)).getInitial());
            assertEquals(Boolean.FALSE, assertInstanceOf(InputDefinition.Bool.class,
                    menu.getInputs().get(1)).getInitial());
            assertEquals("", assertInstanceOf(InputDefinition.Text.class,
                    menu.getInputs().get(2)).getInitial());
            assertEquals("inventory", assertInstanceOf(InputDefinition.SingleOption.class,
                    menu.getInputs().get(3)).getOptions().getFirst().getId());
            ActionDefinition buy = menu.getActions().getFirst();
            assertEquals("OR", buy.getConditions().getItems().get(3).getOperator());
            assertEquals("1000 * $(amount)", assertInstanceOf(ActionStepDefinition.EconomyWithdraw.class,
                    buy.getActions().getFirst()).getAmount());
            assertInstanceOf(ActionStepDefinition.GiveItem.class, buy.getActions().get(1));
            assertInstanceOf(ActionStepDefinition.Return.class, buy.getDenyActions().get(1));
            assertEquals(3, buy.getLimit().get("buy").getPlayer());
            assertEquals(0.0, buy.getCooldown().get("buy"));
        }
    }

    @Test
    void roundTripPreservesDocumentAndDoesNotDuplicateTypeFields() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/menu-shop.json")) {
            var original = mapper.readTree(input);
            MenuDefinition menu = mapper.treeToValue(original, MenuDefinition.class);
            String json = mapper.writeValueAsString(menu);
            MenuDefinition restored = mapper.readValue(json, MenuDefinition.class);
            var result = mapper.readTree(mapper.writeValueAsString(restored));
            // The typed description may gain its explicit default discriminator.
            ((tools.jackson.databind.node.ObjectNode) original.at("/body/1/description")).put("type", "text");
            // JSON's 0 and 0.0 have the same value but different Jackson node types.
            ((tools.jackson.databind.node.ObjectNode) original.at("/actions/0/actions/0")).put("delay", 0.0);
            ((tools.jackson.databind.node.ObjectNode) original.at("/actions/0/cooldown")).put("buy", 0.0);
            assertEquals(original, result);
            assertFalse(json.contains("\"type\":\"text\",\"type\""));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"notice", "confirmation", "multi_action", "dialog_list", "server_links"})
    void supportsEveryDialogType(String type) {
        MenuDefinition menu = mapper.readValue("{\"dialogType\":\"" + type + "\"}", MenuDefinition.class);
        assertEquals(type, mapper.readTree(mapper.writeValueAsString(menu)).get("dialogType").asString());
    }

    @Test
    void readsTypeSpecificButtonsAndReferences() {
        MenuDefinition menu = mapper.readValue("""
                {"noticeAction":{"label":"OK"},"yesButton":{"label":"Yes"},
                 "noButton":{"label":"No"},"exitAction":{"label":"Exit"},
                 "dialogs":[{"menuId":"shop"},{"registryKey":"minecraft:custom"}],
                 "buttonWidth":150,"afterAction":"WAIT_FOR_RESPONSE"}
                """, MenuDefinition.class);
        assertEquals("Yes", menu.getYesButton().getLabel().getValue().asString());
        assertEquals("Exit", menu.getExitAction().getLabel().getValue().asString());
        assertEquals("shop", menu.getDialogs().getFirst().getMenuId());
        assertEquals(AfterAction.WAIT_FOR_RESPONSE, menu.getAfterAction());
    }

    @Test
    void preservesOptionalNullValuesRatherThanInventingDefaults() {
        InputDefinition.NumberRange input = assertInstanceOf(InputDefinition.NumberRange.class,
                mapper.readValue("{\"type\":\"number_range\",\"step\":null}", InputDefinition.class));
        assertNull(input.getStep());
        assertNull(input.getInitial());
        assertNull(mapper.readValue("{}", MenuDefinition.class).getSchemaVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"format\":\"plain\",\"value\":\"hello\"}",
            "{\"format\":\"minimessage\",\"value\":\"<gradient:red:blue>hello</gradient>\"}",
            "{\"format\":\"component\",\"value\":{\"translate\":\"gui.done\"}}"
    })
    void roundTripsStructuredText(String json) {
        TextSpec text = mapper.readValue(json, TextSpec.class);
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(text)));
    }

    @Test
    void rejectsUnknownDiscriminatorsFieldsAndMalformedText() {
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"type\":\"unknown\"}", BodyBlock.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"type\":\"unknown\"}", InputDefinition.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"type\":\"unknown\"}", ActionStepDefinition.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"titel\":\"typo\"}", MenuDefinition.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"id\":\"a\",\"id\":\"b\"}", MenuDefinition.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"format\":\"plain\",\"value\":42}", TextSpec.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue("{\"format\":\"unknown\",\"value\":\"x\"}", TextSpec.class));
    }

    @Test
    void supportsProgrammaticBeansAndPlayerDataAction() {
        MenuDefinition menu = new MenuDefinition();
        menu.setId("new_menu");
        menu.setDialogType(DialogType.NOTICE);
        TextSpec title = new TextSpec();
        title.setFormat("plain");
        title.setValue(mapper.readTree("\"Hello\""));
        menu.setTitle(title);
        MenuDefinition restored = mapper.readValue(mapper.writeValueAsString(menu), MenuDefinition.class);
        assertEquals("Hello", restored.getTitle().getValue().asString());
        var increment = assertInstanceOf(ActionStepDefinition.PlayerDataIncrement.class,
                mapper.readValue("{\"type\":\"player_data_increment\",\"key\":\"visits\",\"amount\":\"1\"}",
                        ActionStepDefinition.class));
        assertEquals("visits", increment.getKey());
    }
}
