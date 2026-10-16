package dialogcanvas.web.menu;

import dialogcanvas.model.*;
import dialogcanvas.storage.*;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verify the distributable pack using the same parser, material catalog and repository as production. */
class VanillaShopPackTest {
    @TempDir Path directory;
    final Path pack = Path.of("../examples/vanilla-shop");

    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void allProductsLoadWithValidReferencesYamlBaselinesAndPrices() throws Exception {
        var mapper = MenuJson.createMapper();
        var catalog = mapper.readTree(Files.readAllBytes(pack.resolve("catalog.json")));
        assertTrue(catalog.get("productCount").asInt() >= 700);
        var materials = new HashSet<String>();
        for (Material material : Material.values()) if (!material.name().startsWith("LEGACY_") && material.isItem() && !material.isAir()) materials.add(material.name());
        var validator = new MenuValidator(new MenuEnvironment(materials, Set.of()));
        var ids = new HashSet<String>();
        try (var files = Files.list(pack.resolve("data/menus"))) {
            for (var file : files.toList()) ids.add(file.getFileName().toString().replace(".json", ""));
        }
        assertEquals(catalog.get("menuCount").asInt(), ids.size());
        for (String id : ids) {
            byte[] json = Files.readAllBytes(pack.resolve("data/menus/" + id + ".json"));
            byte[] yaml = Files.readAllBytes(pack.resolve("menus/" + id + ".yml"));
            var sync = mapper.readTree(Files.readAllBytes(pack.resolve("data/menu-sync/" + id + ".json")));
            assertEquals(MenuRepository.revision(json), sync.get("jsonHash").asString(), id);
            assertEquals(MenuRepository.revision(yaml), sync.get("yamlHash").asString(), id);
            assertEquals(MenuDocuments.decode(json), MenuYaml.decode(yaml), id);
            MenuValidator.Prepared prepared;
            try { prepared = validator.prepare(MenuDocuments.decode(json), ids); }
            catch (ApiException failure) { throw new AssertionError(id + ": " + failure.details(), failure); }
            assertEquals(id, prepared.menu().getId());
            assertFalse(prepared.warnings().stream().anyMatch(w -> w.code().equals("ICON_UNMAPPED")), id);
            for (var button : prepared.menu().getActions()) for (var step : button.getActions()) {
                String amount = switch (step) {
                    case ActionStepDefinition.EconomyWithdraw money -> money.getAmount();
                    case ActionStepDefinition.EconomyDeposit money -> money.getAmount();
                    default -> null;
                };
                if (amount != null) {
                    var values = Map.of("buy_slider", mapper.readTree("3"), "buy_text", mapper.readTree("3"),
                            "sell_slider", mapper.readTree("3"), "sell_text", mapper.readTree("3"));
                    double total = dialogcanvas.engine.action.NumericExpression.evaluate(amount, values);
                    assertTrue(java.math.BigDecimal.valueOf(total).stripTrailingZeros().scale() <= 2, id + ": " + amount);
                }
            }
            for (String folder : List.of("data/menus", "data/menu-sync", "menus")) {
                String extension = folder.equals("menus") ? ".yml" : ".json";
                Path target = directory.resolve(folder + "/" + id + extension);
                Files.createDirectories(target.getParent());
                Files.copy(pack.resolve(folder + "/" + id + extension), target);
            }
        }
        try (var repository = new MenuRepository(new FileStorage(directory.resolve("data"), directory.resolve("menus")), validator)) {
            repository.initialize().join();
            assertTrue(repository.ready());
            assertEquals(ids.size(), repository.completionIds().size());
            var oak = repository.get("vs_item_oak_log").join();
            assertTrue(oak.toString().contains("synced"));
            var menu = repository.definition("vs_item_oak_log").join();
            assertEquals(4, menu.getInputs().size());
            assertEquals(menu.getActions().get(0).getLimit().get("buy").getScope(), menu.getActions().get(1).getLimit().get("buy").getScope());
            assertEquals("1 * $(buy_slider)", ((ActionStepDefinition.EconomyWithdraw) menu.getActions().get(0).getActions().getFirst()).getAmount());
            assertEquals("1 * $(sell_slider)", ((ActionStepDefinition.EconomyDeposit) menu.getActions().get(2).getActions().get(1)).getAmount());
            Map<String, tools.jackson.databind.JsonNode> messageInputs = Map.of("buy_slider", mapper.valueToTree(128), "buy_text", mapper.valueToTree(128),
                    "sell_slider", mapper.valueToTree(128), "sell_text", mapper.valueToTree(128));
            for (int buttonIndex = 0; buttonIndex < 4; buttonIndex++) {
                var message = (ActionStepDefinition.Message) menu.getActions().get(buttonIndex).getActions().getLast();
                var resolved = dialogcanvas.engine.action.ActionMessageTemplate.resolve(message.getText(), messageInputs, token -> token);
                String output = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(new dialogcanvas.dialog.TextRenderer().render(resolved));
                assertEquals(buttonIndex < 2 ? "花费 128 游戏币，购买橡木原木 ×128。" : "出售橡木原木 ×128，获得 128 游戏币。", output);
            }
            // A distributed baseline must allow subsequent web editing and normal YAML synchronization.
            repository.update("vs_item_oak_log", oak.get("revision").asString(), oak.get("menu")).join();
            assertTrue(repository.get("vs_item_oak_log").join().toString().contains("synced"));
        }
    }
}
