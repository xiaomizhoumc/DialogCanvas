package dialogcanvas.web.menu;

import dialogcanvas.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Documentation fixtures use the production parser, validator and repository; no business actions run. */
class WikiExamplesTest {
    @TempDir Path directory;
    final Path pack = Path.of("../examples/wiki/menus");
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    Map<String, JsonNode> documents() throws Exception {
        var result = new TreeMap<String, JsonNode>();
        try (var files = Files.list(pack)) {
            for (var file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                var bytes = Files.readAllBytes(file);
                var doc = MenuYaml.decode(bytes);
                var id = doc.path("id").asString();
                assertEquals(id + ".yml", file.getFileName().toString());
                assertTrue(new String(bytes, StandardCharsets.UTF_8).lines().filter(l -> l.contains("#")).count() >= 8, id);
                assertEquals(1, doc.path("schemaVersion").asInt());
                assertFalse(doc.has("metadata")); assertFalse(doc.has("revision"));
                assertNull(result.put(id, doc));
            }
        }
        assertEquals(10, result.size()); return result;
    }

    MenuValidator validator() { return new FeatureMenuPackTest().validator(); }

    @Test void completeCommentedCollectionUsesActualModelAndReferenceValidation() throws Exception {
        var docs = documents(); var validator = validator();
        for (var entry : docs.entrySet()) {
            MenuValidator.Prepared prepared;
            try { prepared = validator.prepare(entry.getValue(), docs.keySet()); }
            catch (ApiException failure) { throw new AssertionError(entry.getKey() + ": " + failure.details(), failure); }
            assertTrue(prepared.warnings().isEmpty(), entry.getKey() + ": " + prepared.warnings());
            var encoded = MenuYaml.decode(MenuYaml.encode(entry.getValue()));
            assertEquals(entry.getValue(), encoded, entry.getKey() + " semantic YAML roundtrip");
        }
    }

    @Test void numberedImportThenWebSaveExportAndRestartPreserveAllMenus() throws Exception {
        var docs = documents(); Path data = directory.resolve("data"), manual = directory.resolve("menus");
        try (var repo = new MenuRepository(new FileStorage(data, manual), validator())) {
            repo.initialize().join(); Files.createDirectories(manual);
            // Ordered imports only reference menus already committed. No direct formal JSON writes.
            for (var entry : docs.entrySet()) {
                String id = entry.getKey();
                Files.copy(pack.resolve(id + ".yml"), manual.resolve(id + ".yml"));
                try { repo.reloadYaml(id).join(); }
                catch (Exception failure) { throw new AssertionError("Import " + id, failure); }
                var read = repo.get(id).join();
                assertEquals("synced", read.path("syncStatus").asString());
                var saved = repo.update(id, read.path("revision").asString(), read.get("menu")).join();
                assertEquals("synced", saved.path("syncStatus").asString());
                assertEquals(saved.get("menu"), MenuDocuments.decode(Files.readAllBytes(data.resolve("menus/" + id + ".json"))));
                assertEquals(saved.get("menu"), MenuYaml.decode(Files.readAllBytes(manual.resolve(id + ".yml"))));
                var exported = repo.exportYaml(saved.get("menu")).join();
                var imported = repo.importYaml(exported.path("yaml").asString().getBytes(StandardCharsets.UTF_8)).join();
                assertEquals(saved.get("menu"), imported.get("menu"));
            }
            assertEquals(10, repo.completionIds().size());
        }
        try (var restarted = new MenuRepository(new FileStorage(data, manual), validator())) {
            restarted.initialize().join();
            assertEquals(docs.keySet(), new TreeSet<>(restarted.completionIds()));
            for (var id : docs.keySet()) assertEquals("synced", restarted.get(id).join().path("syncStatus").asString());
        }
    }

    @Test void playerDataExplicitNullIsDataNotDeletion() throws Exception {
        var doc = documents().get("wiki_10_player_data").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) doc.at("/actions/0/actions/0")).putNull("value");
        var prepared = validator().prepare(doc, Set.of());
        assertTrue(((dialogcanvas.model.ActionStepDefinition.PlayerDataSet) prepared.menu().getActions().getFirst().getActions().getFirst()).getValue().isNull());
    }
}
