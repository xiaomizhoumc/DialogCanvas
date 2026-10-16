package dialogcanvas.web.menu;

import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.storage.MenuYaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MainMenuRepositoryTest {
    @TempDir Path directory;
    private static JsonNode menu(String id, boolean main) {
        var node = (ObjectNode) MenuRepositoryTest.menu(id); node.put("shortcutEnabled", main); return node;
    }
    private MenuRepository repository() { return new MenuRepository(new FileStorage(directory), MenuRepositoryTest.validator()); }

    @Test void concurrentClaimsHaveOnlyOneWinnerAndPreserveOtherFiles() throws Exception {
        try (var repo = repository()) {
            repo.initialize().join(); assertNull(repo.mainMenuId());
            var a = repo.create(menu("alpha", false)).join(); var b = repo.create(menu("beta", false)).join();
            byte[] original = Files.readAllBytes(directory.resolve("menus/beta.json"));
            var first = repo.update("alpha", a.get("revision").asString(), menu("alpha", true));
            var second = repo.update("beta", b.get("revision").asString(), menu("beta", true));
            first.join(); var conflict = MenuRepositoryTest.failed(second::join, "SHORTCUT_MENU_CONFLICT");
            assertEquals(409, conflict.status()); assertEquals("/menu/shortcutEnabled", conflict.details().getFirst().path());
            assertTrue(conflict.getMessage().contains("alpha")); assertEquals("alpha", repo.mainMenuId());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("menus/beta.json")));
            assertEquals(1, repo.list().join().get("items").valueStream().filter(item -> item.get("shortcutEnabled").asBoolean()).count());
            MenuRepositoryTest.failed(() -> repo.validate(menu("beta", true)).join(), "SHORTCUT_MENU_CONFLICT");
            assertNotNull(repo.validate(menu("alpha", true)).join());
        }
    }
    @Test void switchingAndRestartPreserveUniqueMainAndDeletionClearsIt() {
        try (var repo = repository()) {
            repo.initialize().join(); var a = repo.create(menu("alpha", true)).join();
            MenuRepositoryTest.failed(() -> repo.create(menu("beta", true)).join(), "SHORTCUT_MENU_CONFLICT");
            repo.update("alpha", a.get("revision").asString(), menu("alpha", false)).join(); assertNull(repo.mainMenuId());
            repo.create(menu("beta", true)).join(); assertEquals("beta", repo.mainMenuId());
        }
        try (var repo = repository()) {
            repo.initialize().join(); assertEquals("beta", repo.mainMenuId());
            assertTrue(repo.definition("beta").join().getShortcutEnabled());
            repo.delete("beta", repo.get("beta").join().get("revision").asString()).join(); assertNull(repo.mainMenuId());
        }
    }
    @Test void yamlCannotClaimAnAlreadyOccupiedShortcut() throws Exception {
        try (var repo = repository()) {
            repo.initialize().join(); repo.create(menu("alpha", true)).join(); repo.create(menu("beta", false)).join();
            byte[] before = Files.readAllBytes(directory.resolve("menus/beta.json"));
            Files.write(directory.resolve("manual-menus/beta.yml"), MenuYaml.encode(menu("beta", true)));
            MenuRepositoryTest.failed(() -> repo.reloadYaml("beta").join(), "SHORTCUT_MENU_CONFLICT");
            assertEquals("alpha", repo.mainMenuId()); assertArrayEquals(before, Files.readAllBytes(directory.resolve("menus/beta.json")));
            var batch = repo.reloadAllYaml().join().get("items");
            assertEquals(1, batch.valueStream().filter(item -> item.get("success").asBoolean()).count());
            assertEquals("alpha", repo.mainMenuId());
        }
    }
    @Test void registryFailureRestoresFileAndShortcutOwnership() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(directory); var registry = new MenuRepositoryTest.FaultRegistry();
        try (var repo = new MenuRepository(files, MenuRepositoryTest.validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var a = repo.create(menu("alpha", false)).join();
            byte[] before = Files.readAllBytes(directory.resolve("menus/alpha.json")); registry.failNext = true;
            MenuRepositoryTest.failed(() -> repo.update("alpha", a.get("revision").asString(), menu("alpha", true)).join(), "REGISTRY_PUBLISH_FAILED");
            assertNull(repo.mainMenuId()); assertArrayEquals(before, Files.readAllBytes(directory.resolve("menus/alpha.json")));
        }
    }
    @Test void duplicateFormalOrLegacyMenusAreRejectedBeforeMigrationWrites() throws Exception {
        Files.createDirectories(directory.resolve("menus"));
        byte[] first = MenuJson.createMapper().writeValueAsBytes(menu("alpha", true));
        Files.write(directory.resolve("menus/alpha.json"), first);
        byte[] second = MenuYaml.encode(menu("beta", true)); Files.write(directory.resolve("menus/beta.yml"), second);
        try (var repo = repository()) {
            MenuRepositoryTest.failed(() -> repo.initialize().join(), "MENU_SERVICE_UNAVAILABLE"); assertFalse(repo.ready());
            assertNull(repo.mainMenuId()); assertFalse(Files.exists(directory.resolve("menus/beta.json")));
            assertArrayEquals(first, Files.readAllBytes(directory.resolve("menus/alpha.json")));
            assertArrayEquals(second, Files.readAllBytes(directory.resolve("menus/beta.yml")));
        }
        Files.delete(directory.resolve("menus/beta.yml")); Files.write(directory.resolve("menus/beta.json"), MenuJson.createMapper().writeValueAsBytes(menu("beta", true)));
        try (var repo = repository()) {
            MenuRepositoryTest.failed(() -> repo.initialize().join(), "MENU_SERVICE_UNAVAILABLE"); assertNull(repo.mainMenuId());
        }
    }
}
