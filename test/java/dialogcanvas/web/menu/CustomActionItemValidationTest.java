package dialogcanvas.web.menu;

import dialogcanvas.model.*;
import dialogcanvas.storage.MenuYaml;
import dialogcanvas.storage.MenuDocuments;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CustomActionItemValidationTest {
    final MenuValidator validator = new MenuValidator(new MenuEnvironment(Set.of("DIAMOND_SWORD", "minecraft:diamond_sword"), Set.of(), Set.of("minecraft:sharpness"), Set.of("minecraft:attack_damage")));
    ObjectNode menu() {
        var menu = (ObjectNode) MenuRepositoryTest.menu("custom_sword");
        var button = menu.putObject("noticeAction"); button.put("id", "get"); button.put("label", "获得超级钻石剑");
        var step = button.putArray("actions").addObject(); step.put("type", "give_item"); step.put("material", "diamond_sword"); step.put("amount", "1");
        step.putObject("enchantments").put("sharpness", 10); step.put("unbreakable", true);
        step.putArray("attributes").addObject().put("attribute", "attack_damage").put("amount", 100);
        return menu;
    }
    ObjectNode step(ObjectNode menu) { return (ObjectNode) menu.at("/noticeAction/actions/0"); }
    @Test void customFieldsSurviveSharedJsonAndYamlRoundtrip() {
        var prepared = validator.prepare(menu(), Set.of());
        var json = MenuDocuments.decode(prepared.json().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(10, json.at("/noticeAction/actions/0/enchantments/sharpness").intValue());
        assertTrue(validator.prepare(json, Set.of()).menu().getNoticeAction().getActions().getFirst() instanceof ActionStepDefinition.GiveItem);
        var yaml = MenuYaml.decode(MenuYaml.encode(json));
        assertEquals(json, yaml);
    }
    @Test void documentedJsonAndCommentedYamlRepresentTheSameValidMenu() throws Exception {
        var json = MenuDocuments.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../examples/custom_weapon_shop.json")));
        var yaml = MenuYaml.decode(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../examples/custom_weapon_shop.yml")));
        assertEquals(json, yaml);
        var actual = new MenuValidator(new MenuEnvironment(Set.of("DIAMOND_SWORD", "minecraft:diamond_sword"), Set.of("ENTITY_PLAYER_LEVELUP"),
                Set.of("minecraft:sharpness", "minecraft:unbreaking"), Set.of("minecraft:attack_damage")));
        assertDoesNotThrow(() -> actual.prepare(json, Set.of()));
    }
    @Test void unknownItemAndInvalidEnchantAreLocatedBeforeAnyFileCommit() {
        var menu = menu(); step(menu).put("material", "other:diamond_sword");
        assertEquals("UNKNOWN_MATERIAL", assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of())).details().getFirst().code());
        step(menu).put("material", "diamond_sword"); ((ObjectNode) step(menu).get("enchantments")).put("sharpness", 256);
        assertEquals("/menu/noticeAction/actions/0/enchantments/sharpness", assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of())).details().getFirst().path());
    }
    @Test void invalidAttributeSlotAndDuplicateAliasAreRejected() {
        var menu = menu(); ((ObjectNode) step(menu).at("/attributes/0")).put("slot", "nowhere");
        assertEquals("INVALID_SLOT", assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of())).details().getFirst().code());
        ((ObjectNode) step(menu).at("/attributes/0")).remove("slot");
        ((ObjectNode) step(menu).get("enchantments")).put("minecraft:sharpness", 3);
        assertEquals("DUPLICATE_ENCHANTMENT", assertThrows(ApiException.class, () -> validator.prepare(menu, Set.of())).details().getFirst().code());
    }
}
