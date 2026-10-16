package dialogcanvas.dialog.icon;

import dialogcanvas.model.*;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.SpriteObjectContents;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class VanillaIconResolverTest {
    final VanillaIconResolver resolver = new VanillaIconResolver();
    ButtonIconDefinition icon(String material) {
        var icon = new ButtonIconDefinition(); icon.setMaterial(material); return icon;
    }
    @ParameterizedTest @CsvSource({
        "BONE,minecraft:items,minecraft:item/bone",
        "ENDER_PEARL,minecraft:items,minecraft:item/ender_pearl",
        "BLAZE_ROD,minecraft:items,minecraft:item/blaze_rod",
        "DIAMOND_SWORD,minecraft:items,minecraft:item/diamond_sword",
        "STONE,minecraft:blocks,minecraft:block/stone"})
    void explicitMappingProducesNativeSprite(String material, String atlas, String sprite) {
        var result = resolver.resolve(icon(material)); assertTrue(result.success()); assertNull(result.error());
        var component = assertInstanceOf(ObjectComponent.class, result.icon());
        var contents = assertInstanceOf(SpriteObjectContents.class, component.contents());
        assertEquals(atlas, contents.atlas().asString()); assertEquals(sprite, contents.sprite().asString());
        assertEquals(NamedTextColor.WHITE, component.color());
        assertEquals(ShadowColor.none(), component.shadowColor());
        String serialized = GsonComponentSerializer.gson().serialize(component);
        // Adventure omits the default blocks atlas from JSON; decoding must restore it.
        if (!atlas.equals(SpriteObjectContents.DEFAULT_ATLAS.asString())) assertTrue(serialized.contains(atlas));
        assertTrue(serialized.contains(sprite));
        assertEquals(component, GsonComponentSerializer.gson().deserialize(serialized));
    }
    @ParameterizedTest @ValueSource(strings = {"bone", "minecraft:bone", " minecraft:BONE "})
    void acceptsVanillaAliases(String material) { assertTrue(resolver.resolve(icon(material)).success()); }
    @ParameterizedTest @ValueSource(strings = {"DCMENU_INVALID_ITEM", "CHEST", "PLAYER_HEAD", "AIR", "custom:bone", "", "item/bone"})
    void doesNotGuessUnsupportedTextures(String material) {
        var result = resolver.resolve(icon(material)); assertFalse(result.success()); assertNotNull(result.error()); assertNull(result.icon());
    }
    @Test void catalogCoversMostVanillaItemsAndPreservesNonTrivialModelMappings() {
        assertTrue(VanillaIconResolver.mappings().size() > 1300);
        assertTrue(resolver.resolve(icon("DIAMOND")).success());
        assertEquals("minecraft:block/oak_log", VanillaIconResolver.mappings().get("OAK_LOG").sprite());
        assertEquals("minecraft:item/iron_pickaxe", VanillaIconResolver.mappings().get("IRON_PICKAXE").sprite());
        assertThrows(UnsupportedOperationException.class, () -> VanillaIconResolver.mappings().clear());
    }
    @Test void absentIconDoesNotWarnAndIncompleteOrUnknownTypeIsReported() {
        assertNull(resolver.resolve(null).error());
        assertEquals("MISSING_ICON_MATERIAL", resolver.resolve(new ButtonIconDefinition()).error());
        var icon = icon("BONE"); icon.setType("sprite");
        assertTrue(resolver.resolve(icon).error().startsWith("UNSUPPORTED_ICON_TYPE"));
    }
    @Test void iconPojoRoundTripsAndOldButtonHasNoIcon() {
        var mapper = MenuJson.createMapper();
        var button = mapper.readValue("""
            {"id":"bone","label":"&f骨头","icon":{"type":"item","material":"BONE"}}
            """, ActionDefinition.class);
        assertEquals("BONE", mapper.readValue(mapper.writeValueAsString(button), ActionDefinition.class).getIcon().getMaterial());
        assertNull(mapper.readValue("{\"label\":\"&f旧菜单\"}", ActionDefinition.class).getIcon());
    }
}
