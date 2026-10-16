package dialogcanvas.web.menu;

import dialogcanvas.engine.action.ActionServices;
import dialogcanvas.engine.limit.LimitService;
import dialogcanvas.engine.production.*;
import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.*;
import io.papermc.paper.dialog.DialogResponseView;
import org.bukkit.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual parser/validator/repository plus injected-economy production transactions, not a YAML-only linter. */
class FeatureMenuPackTest {
    @TempDir Path directory;
    final Path pack = Path.of("../examples/feature-menus/menus");
    ServerMock server;
    @BeforeEach void setup() { server = MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    Map<String, JsonNode> documents() throws Exception {
        var result = new TreeMap<String, JsonNode>();
        try (var files = Files.list(pack)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                var document = MenuYaml.decode(bytes); String id = document.path("id").asString();
                assertEquals(id + ".yml", file.getFileName().toString());
                assertTrue(new String(bytes, StandardCharsets.UTF_8).lines().filter(line -> line.contains("#")).count() >= 8, id + " needs detailed comments");
                assertNull(result.put(id, document));
            }
        }
        assertEquals(10, result.size()); return result;
    }
    MenuValidator validator() {
        var materials = new HashSet<String>();
        for (Material material : Material.values()) if (!material.name().startsWith("LEGACY_") && material.isItem() && !material.isAir()) {
            materials.add(material.name()); materials.add(material.getKey().asString());
        }
        var sounds = new HashSet<String>();
        for (var field : Sound.class.getFields()) if (field.getType() == Sound.class) sounds.add(field.getName());
        var enchantments = new HashSet<String>(); var attributes = new HashSet<String>();
        org.bukkit.Registry.ENCHANTMENT.forEach(value -> enchantments.add(value.getKey().asString()));
        org.bukkit.Registry.ATTRIBUTE.forEach(value -> attributes.add(value.getKey().asString()));
        var validator = new MenuValidator(new MenuEnvironment(materials, sounds, enchantments, attributes));
        validator.productionConnected(true); validator.economyProvider("injected test economy"); return validator;
    }
    @Test void tenCommentedMenusValidateWithCompleteReferencesAndFeatureCoverage() throws Exception {
        var documents = documents(); var validator = validator();
        for (var entry : documents.entrySet()) {
            MenuValidator.Prepared prepared;
            try { prepared = validator.prepare(entry.getValue(), documents.keySet()); }
            catch (ApiException failure) { throw new AssertionError(entry.getKey() + ": " + failure.details(), failure); }
            assertTrue(prepared.warnings().isEmpty(), entry.getKey() + ": " + prepared.warnings());
            assertFalse(Boolean.TRUE.equals(prepared.menu().getShortcutEnabled()));
            assertNull(prepared.menu().getMetadata(), "Do not forge service-maintained revisions");
        }
        assertTrue(values(documents.values(), "dialogType").containsAll(Set.of("notice", "confirmation", "multi_action")));
        assertTrue(values(documents.values(), "afterAction").containsAll(Set.of("CLOSE", "NONE", "WAIT_FOR_RESPONSE")));
        assertTrue(values(documents.values(), "format").containsAll(Set.of("plain", "legacy", "minimessage", "component")));
        assertTrue(values(documents.values(), "reset").containsAll(Set.of("DAILY", "WEEKLY", "MONTHLY", "TIMED", "NEVER")));
        assertTrue(values(documents.values(), "type").containsAll(Set.of("bool", "text", "number_range", "single_option",
                "permission", "numeric", "string", "placeholder", "player_data", "economy_withdraw", "economy_deposit",
                "give_item", "take_item", "player_data_set", "player_data_increment", "command", "message", "play_sound",
                "open_menu", "refresh_menu", "close_menu", "return")));
        var shop = documents.get("examples_log_shop");
        assertEquals(shop.at("/actions/0/limit/buy/scope"), shop.at("/actions/1/limit/buy/scope"));
        assertEquals("OAK_LOG", shop.at("/inputs/3/inventoryMaximumMaterial").asString());
        assertEquals("OAK_LOG", shop.at("/inputs/4/inventoryMaximumMaterial").asString());
    }
    Set<String> values(Collection<JsonNode> documents, String property) {
        var result = new HashSet<String>(); documents.forEach(node -> values(node, property, result)); return result;
    }
    void values(JsonNode node, String property, Set<String> values) {
        if (node.isObject()) {
            if (node.has(property) && node.get(property).isString()) values.add(node.get(property).asString());
            node.properties().forEach(entry -> values(entry.getValue(), property, values));
        } else if (node.isArray()) node.forEach(child -> values(child, property, values));
    }
    @Test void allTenCanBootstrapTogetherWithCommentBaselinesAndSubsequentWebSave() throws Exception {
        var mapper = MenuJson.createMapper(); var documents = documents();
        Path data = directory.resolve("data"), manual = directory.resolve("menus");
        Files.createDirectories(data.resolve("menus")); Files.createDirectories(data.resolve("menu-sync")); Files.createDirectories(manual);
        for (var entry : documents.entrySet()) {
            String id = entry.getKey(); byte[] yaml = Files.readAllBytes(pack.resolve(id + ".yml"));
            byte[] json = MenuDocuments.encode(entry.getValue());
            Files.write(data.resolve("menus/" + id + ".json"), json);
            Files.write(manual.resolve(id + ".yml"), yaml);
            Files.write(data.resolve("menu-sync/" + id + ".json"), mapper.writeValueAsBytes(mapper.createObjectNode()
                    .put("jsonHash", MenuRepository.revision(json)).put("yamlHash", MenuRepository.revision(yaml))));
        }
        try (var repository = new MenuRepository(new FileStorage(data, manual), validator())) {
            repository.initialize().join(); assertEquals(10, repository.completionIds().size());
            for (String id : documents.keySet()) assertTrue(repository.get(id).join().toString().contains("synced"), id);
            var home = repository.get("examples_home").join();
            var updated = repository.update("examples_home", home.get("revision").asString(), home.get("menu")).join();
            assertEquals(1, updated.at("/menu/metadata/version").intValue());
            assertTrue(repository.get("examples_home").join().toString().contains("synced"));
            repository.reloadYaml("examples_log_shop").join();
            assertTrue(repository.get("examples_log_shop").join().toString().contains("synced"));
        }
        try (var restarted = new MenuRepository(new FileStorage(data, manual), validator())) {
            restarted.initialize().join(); assertEquals(10, restarted.completionIds().size());
        }
    }
    @Test void exactPackShopBuysSellsSharesQuotaAndRejectsStaleInventory() throws Exception {
        var menu = validator().prepare(documents().get("examples_log_shop"), documents().keySet()).menu();
        PlayerMock player = server.addPlayer(); var scheduler = new Scheduler(MockBukkit.createMockPlugin());
        var store = new ProductionStore(directory.resolve("data")); store.initialize().join();
        var clock = new MutableClock(); var money = new Money();
        var engine = new ProductionActionEngine(scheduler, store, new LimitService(directory, scheduler, clock, ZoneOffset.UTC),
                (p, token) -> Optional.empty(), new ActionServices.Menus() {
                    public CompletableFuture<Void> open(org.bukkit.entity.Player p, String id) { return CompletableFuture.completedFuture(null); }
                    public CompletableFuture<Void> refresh(org.bukkit.entity.Player p, MenuDefinition definition) { return CompletableFuture.completedFuture(null); }
                }, Logger.getAnonymousLogger(), money);
        try {
            trade(engine, menu, player, 0, 2, "1", 0, "0", false); assertEquals(1000, money.balance); assertEquals(0, logs(player));
            trade(engine, menu, player, 0, 2, "1", 0, "0", true); assertEquals(998, money.balance); assertEquals(2, logs(player));
            trade(engine, menu, player, 1, 1, "1", 0, "0", true); assertEquals(998, money.balance); // Shared cooldown blocks other input method.
            clock.now = clock.now.plusSeconds(3);
            trade(engine, menu, player, 1, 1, "64", 0, "0", true); assertEquals(934, money.balance); assertEquals(66, logs(player));
            clock.now = clock.now.plusSeconds(3);
            trade(engine, menu, player, 0, 64, "1", 0, "0", true); assertEquals(934, money.balance); assertEquals(66, logs(player)); // 130 > daily 128.
            trade(engine, menu, player, 3, 1, "1", 0, "3", true); assertEquals(937, money.balance); assertEquals(63, logs(player));
            trade(engine, menu, player, 2, 1, "1", 1, "0", true); assertEquals(938, money.balance); assertEquals(62, logs(player));
            clock.now = clock.now.plusSeconds(3);
            trade(engine, menu, player, 3, 1, "1", 0, "63", true); assertEquals(938, money.balance); assertEquals(62, logs(player));
            assertFalse(store.blocked(player.getUniqueId()));
            String quota = Files.readString(directory.resolve("playerdata/" + player.getUniqueId() + ".yml"));
            assertTrue(quota.contains("count: 66"), "Sales must not restore purchase quota");
        } finally { await(engine.closeAsync()); scheduler.close(); }
    }
    void trade(ProductionActionEngine engine, MenuDefinition menu, PlayerMock player, int button, int buy, String buyText, int sell, String sellText, boolean confirm) throws Exception {
        var response = new DialogResponseView() {
            public net.kyori.adventure.nbt.api.BinaryTagHolder payload() { throw new UnsupportedOperationException(); }
            public String getText(String key) { return key.equals("buy_text") ? buyText : key.equals("sell_text") ? sellText : null; }
            public Float getFloat(String key) { return key.equals("buy_slider") ? (float) buy : key.equals("sell_slider") ? (float) sell : null; }
            public Boolean getBoolean(String key) { return key.equals("confirm") ? confirm : null; }
        };
        await(engine.executeAsync(player, menu, menu.getActions().get(button), response));
    }
    <T> T await(CompletableFuture<T> work) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!work.isDone() && System.nanoTime() < deadline) { server.getScheduler().performOneTick(); Thread.sleep(2); }
        return work.get(1, TimeUnit.SECONDS);
    }
    int logs(PlayerMock player) { return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull).filter(item -> item.getType() == Material.OAK_LOG).mapToInt(org.bukkit.inventory.ItemStack::getAmount).sum(); }
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static final class Money implements ActionServices.Economy {
        double balance = 1000;
        public CompletableFuture<ActionServices.Change> deposit(UUID player, double amount) { return change(amount); }
        public CompletableFuture<ActionServices.Change> withdraw(UUID player, double amount) { return change(-amount); }
        CompletableFuture<ActionServices.Change> change(double delta) {
            assertTrue(Bukkit.isPrimaryThread());
            if (balance + delta < 0) return CompletableFuture.failedFuture(new IllegalStateException("insufficient test balance"));
            balance += delta;
            return CompletableFuture.completedFuture(new ActionServices.Change() {
                public CompletableFuture<Void> rollback() { assertTrue(Bukkit.isPrimaryThread()); balance -= delta; return CompletableFuture.completedFuture(null); }
                public Object recoveryData() { return Map.of("amount", delta); }
            });
        }
    }
}
