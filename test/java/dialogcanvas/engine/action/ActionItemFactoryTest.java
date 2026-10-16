package dialogcanvas.engine.action;

import dialogcanvas.model.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class ActionItemFactoryTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    ActionStepDefinition.GiveItem definition(String json) {
        return (ActionStepDefinition.GiveItem) MenuJson.createMapper().readValue(json, ActionStepDefinition.class);
    }

    @Test void fullCustomSwordHasRealEnchantsMetadataAndAdditionalAttributes() {
        var value = definition("""
            {"type":"give_item","material":"minecraft:diamond_sword","amount":"1",
             "name":{"format":"minimessage","value":"<gold>超级钻石剑"},"lore":["&7专属武器"],
             "unbreakable":true,"enchantments":{"sharpness":10,"minecraft:unbreaking":3},
             "attributes":[{"attribute":"attack_damage","amount":100},{"attribute":"attack_speed","amount":0.2,"operation":"ADD_SCALAR","slot":"mainhand"}]}
            """);
        var stack = new ActionItemFactory().create(value, true); var meta = stack.getItemMeta();
        assertEquals(Material.DIAMOND_SWORD, stack.getType());
        assertEquals(10, meta.getEnchantLevel(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"))));
        assertTrue(meta.isUnbreakable()); assertNotNull(meta.displayName()); assertEquals(1, meta.lore().size());
        assertFalse(meta.hasEnchantmentGlintOverride());
        assertTrue(meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE).stream().anyMatch(a -> a.getAmount() == 100));
        for (var entry : Material.DIAMOND_SWORD.getDefaultAttributeModifiers().entries())
            assertTrue(meta.getAttributeModifiers(entry.getKey()).stream().anyMatch(a -> a.getKey().equals(entry.getValue().getKey())));
        var roundtrip = definition(MenuJson.createMapper().writeValueAsString(value));
        assertTrue(stack.isSimilar(new ActionItemFactory().create(roundtrip, true)));
    }

    @Test void lowerCaseAndLegacyMaterialsRemainCompatibleWithoutFakeGlint() {
        var factory = new ActionItemFactory();
        assertTrue(factory.create(definition("""
{"type":"give_item","material":"iron_ingot","glow":true}
"""), true)
                .isSimilar(factory.create(definition("""
{"type":"give_item","material":"IRON_INGOT"}
"""), true)));
    }

    @Test void externalNamespaceNeverFallsBackToVanillaOrSilentlyDropsInvalidEnchant() {
        var factory = new ActionItemFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.create(definition("""
{"type":"give_item","material":"other:diamond_sword"}
"""), true));
        assertThrows(IllegalArgumentException.class, () -> factory.create(definition("""
{"type":"give_item","material":"diamond_sword","enchantments":{"not_real":1}}
"""), true));
        assertThrows(IllegalArgumentException.class, () -> factory.create(definition("""
{"type":"give_item","material":"diamond_sword","enchantments":{"sharpness":256}}
"""), true));
    }
}
