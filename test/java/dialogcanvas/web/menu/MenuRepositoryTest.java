package dialogcanvas.web.menu;

import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class MenuRepositoryTest {
    @TempDir Path directory;

    @Test void callbackLeaseRejectsDraftsUpdatesDeletionRecreationReloadAndClosedService() {
        var repo = new MenuRepository(new FileStorage(directory), validator());
        try {
            repo.initialize().join();
            var created = repo.create(menu("lease")).join();
            var definition = repo.definition("lease").join();
            var lease = repo.callbackVersion(definition);
            assertTrue(lease.getAsBoolean());
            definition.setTitle(null);
            assertFalse(repo.callbackVersion(definition).getAsBoolean(), "Draft is not a committed document");
            var changed = repo.update("lease", created.get("revision").asString(), menu("lease")).join();
            assertFalse(lease.getAsBoolean());
            var updated = repo.callbackVersion(repo.definition("lease").join());
            assertTrue(updated.getAsBoolean());
            repo.delete("lease", changed.get("revision").asString()).join();
            repo.create(menu("lease")).join();
            assertFalse(updated.getAsBoolean());
            var reloaded = repo.callbackVersion(repo.definition("lease").join());
            repo.reloadYaml("lease").join();
            assertFalse(reloaded.getAsBoolean());
            var closing = repo.callbackVersion(repo.definition("lease").join());
            repo.close();
            assertFalse(closing.getAsBoolean());
        } finally { repo.close(); }
    }
    @Test void legacyNoneWithoutPauseLoadsAcrossRestartWithoutChangingFilesOrRevision() throws Exception {
        var document = (tools.jackson.databind.node.ObjectNode) menu("legacy_none");
        document.put("afterAction", "NONE");
        String revision;
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join();
            revision = repo.create(document).join().get("revision").asString();
        }
        byte[] before = Files.readAllBytes(directory.resolve("menus/legacy_none.json"));
        try (var restart = new MenuRepository(new FileStorage(directory), validator())) {
            restart.initialize().join();
            assertTrue(restart.ready());
            var loaded = restart.get("legacy_none").join();
            assertEquals(revision, loaded.get("revision").asString());
            assertFalse(loaded.get("menu").has("pause"));
            assertFalse(loaded.get("warnings").toString().contains("LEGACY_PAUSE_DEFAULT"));
            assertFalse(dialogcanvas.dialog.DialogBehavior.effectivePause(restart.definition("legacy_none").join()));
        }
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("menus/legacy_none.json")));
    }
    static MenuValidator validator() { return new MenuValidator(new MenuEnvironment(Set.of("DIAMOND_SWORD", "STONE", "minecraft:stone"), Set.of("ENTITY_PLAYER_LEVELUP", "minecraft:entity.player.levelup"))); }
    static JsonNode menu(String id) { return MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"title\":\"&6欢迎\",\"dialogType\":\"notice\"}"); }
    static ApiException failed(Runnable action, String code) {
        var failure = assertInstanceOf(ApiException.class, assertThrows(CompletionException.class, action::run).getCause());
        assertEquals(code, failure.code()); return failure;
    }
    static class FaultStore implements MenuPersistence {
        final FileStorage files;
        int failWriteAt;
        boolean failDelete;
        final Set<String> threads = new HashSet<>();
        FaultStore(Path directory) { files = new FileStorage(directory); }
        public void initialize() throws IOException { threads.add(Thread.currentThread().getName()); files.initialize(); }
        public Optional<byte[]> readMenuBytes(String id) throws IOException { threads.add(Thread.currentThread().getName()); return files.readMenuBytes(id); }
        public void writeMenuBytes(String id, byte[] bytes) throws IOException {
            threads.add(Thread.currentThread().getName());
            if (failWriteAt > 0 && --failWriteAt == 0) throw new IOException("Injected write failure with private path");
            files.writeMenuBytes(id, bytes);
        }
        public boolean removeMenu(String id) throws IOException { if (failDelete) throw new IOException("Injected delete failure"); return files.removeMenu(id); }
        public List<String> menuIds() throws IOException { return files.menuIds(); }
        public Optional<byte[]> readAuxiliary(Auxiliary kind, String id) throws IOException { return files.readAuxiliary(kind, id); }
        public void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException { files.writeAuxiliary(kind, id, bytes); }
        public boolean removeAuxiliary(Auxiliary kind, String id) throws IOException { return files.removeAuxiliary(kind, id); }
        public List<String> auxiliaryIds(Auxiliary kind) throws IOException { return files.auxiliaryIds(kind); }
        public void close() { files.close(); }
    }
    static class FaultRegistry extends MenuRegistry {
        boolean failNext;
        public void publish(Map<String, Entry> next) {
            super.publish(next);
            if (failNext) { failNext = false; throw new IllegalStateException("Injected failure after publication"); }
        }
    }

    @Test void createsYamlAndImmutableSnapshotWithStableRevisionAcrossRestart() throws Exception {
        var files = new FaultStore(directory); var registry = new MenuRegistry(); String revision;
        try (var repo = new MenuRepository(files, validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var created = repo.create(menu("welcome")).join(); revision = created.get("revision").asString();
            byte[] bytes = Files.readAllBytes(directory.resolve("menus/welcome.json"));
            assertEquals(revision, MenuRepository.revision(bytes));
            assertFalse(Files.readString(directory.resolve("menus/welcome.json")).contains("sha256:"));
            assertEquals(created.get("menu"), MenuYaml.decode(bytes));
            assertEquals(1, created.get("menu").get("metadata").get("version").asInt());
            var definition = repo.definition("welcome").join(); definition.setId("changed");
            assertEquals("welcome", repo.get("welcome").join().get("menu").get("id").asString());
            assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().clear());
            assertEquals(Set.of("DCMenu-menus"), files.threads);
        }
        try (var restart = new MenuRepository(new FileStorage(directory), validator())) {
            restart.initialize().join(); assertEquals(revision, restart.get("welcome").join().get("revision").asString());
        }
    }

    @Test void concurrentUpdatesWithSameRevisionAllowOnlyOneCommit() {
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var created = repo.create(menu("welcome")).join(); String revision = created.get("revision").asString();
            var first = repo.update("welcome", revision, menu("welcome"));
            var second = repo.update("welcome", revision, menu("welcome"));
            assertEquals(2, first.join().get("menu").get("metadata").get("version").asInt());
            failed(second::join, "REVISION_CONFLICT");
            failed(() -> repo.update("welcome", null, menu("welcome")).join(), "REVISION_REQUIRED");
            failed(() -> repo.create(menu("welcome")).join(), "MENU_ALREADY_EXISTS");
        }
    }

    @Test void failedWritePreservesExactFileAndRegistry() throws Exception {
        var files = new FaultStore(directory); var registry = new MenuRegistry();
        try (var repo = new MenuRepository(files, validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var created = repo.create(menu("welcome")).join(); var old = registry.snapshot();
            byte[] bytes = Files.readAllBytes(directory.resolve("menus/welcome.json")); files.failWriteAt = 1;
            failed(() -> repo.update("welcome", created.get("revision").asString(), menu("welcome")).join(), "STORAGE_WRITE_FAILED");
            assertEquals(old, registry.snapshot()); assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("menus/welcome.json")));
        }
    }

    @Test void publicationFailureRestoresFileAndRegistryEvenIfPublisherChangedReference() throws Exception {
        var files = new FaultStore(directory); var registry = new FaultRegistry();
        try (var repo = new MenuRepository(files, validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var created = repo.create(menu("welcome")).join(); var old = registry.snapshot();
            byte[] bytes = Files.readAllBytes(directory.resolve("menus/welcome.json")); registry.failNext = true;
            failed(() -> repo.update("welcome", created.get("revision").asString(), menu("welcome")).join(), "REGISTRY_PUBLISH_FAILED");
            assertEquals(old, registry.snapshot()); assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("menus/welcome.json")));
            registry.failNext = true;
            failed(() -> repo.create(menu("new_menu")).join(), "REGISTRY_PUBLISH_FAILED");
            assertFalse(Files.exists(directory.resolve("menus/new_menu.json"))); assertEquals(old, registry.snapshot());
        }
    }

    @Test void failedCompensationMakesServiceUnavailableRatherThanReportingSuccess() {
        var files = new FaultStore(directory); var registry = new FaultRegistry();
        try (var repo = new MenuRepository(files, validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var created = repo.create(menu("welcome")).join(); registry.failNext = true; files.failWriteAt = 2;
            failed(() -> repo.update("welcome", created.get("revision").asString(), menu("welcome")).join(), "MENU_STATE_UNCERTAIN");
            assertFalse(repo.ready()); failed(() -> repo.get("welcome").join(), "MENU_SERVICE_UNAVAILABLE");
            failed(() -> repo.create(menu("next")).join(), "MENU_SERVICE_UNAVAILABLE");
        }
    }
    @Test void publicationCompensationCannotOverwriteExternallyChangedFormalJson() throws Exception {
        Path file = directory.resolve("menus/welcome.json"); byte[] external = "external formal change".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var registry = new MenuRegistry() {
            int publications;
            @Override public void publish(Map<String, Entry> next) {
                super.publish(next);
                if (++publications == 3) {
                    try { Files.write(file, external); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    throw new IllegalStateException("publish failed after external change");
                }
            }
        };
        try (var repo = new MenuRepository(new FileStorage(directory), validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var saved = repo.create(menu("welcome")).join();
            failed(() -> repo.update("welcome", saved.get("revision").asString(), saved.get("menu")).join(), "MENU_STATE_UNCERTAIN");
            assertArrayEquals(external, Files.readAllBytes(file)); assertFalse(repo.ready());
        }
    }

    @Test void deleteFailureAndPublicationFailureRestoreOriginalMenu() throws Exception {
        var files = new FaultStore(directory); var registry = new FaultRegistry();
        try (var repo = new MenuRepository(files, validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var created = repo.create(menu("welcome")).join(); var old = registry.snapshot();
            byte[] bytes = Files.readAllBytes(directory.resolve("menus/welcome.json")); String revision = created.get("revision").asString();
            files.failDelete = true; failed(() -> repo.delete("welcome", revision).join(), "STORAGE_DELETE_FAILED");
            assertEquals(old, registry.snapshot()); assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("menus/welcome.json")));
            files.failDelete = false; registry.failNext = true;
            failed(() -> repo.delete("welcome", revision).join(), "STORAGE_DELETE_FAILED");
            assertEquals(old, registry.snapshot()); assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("menus/welcome.json")));
            repo.delete("welcome", revision).join(); assertFalse(Files.exists(directory.resolve("menus/welcome.json")));
            assertTrue(registry.snapshot().isEmpty()); failed(() -> repo.get("welcome").join(), "MENU_NOT_FOUND");
        }
    }

    @Test void referencesInDenialBranchesBlockDeletionUntilRemoved() {
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var target = repo.create(menu("target")).join();
            var source = MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"source\",\"title\":\"Source\",\"dialogType\":\"notice\",\"noticeAction\":{\"id\":\"open\",\"label\":\"Open\",\"denyActions\":[{\"type\":\"open_menu\",\"menuId\":\"target\"}]}}");
            var saved = repo.create(source).join();
            var failure = failed(() -> repo.delete("target", target.get("revision").asString()).join(), "MENU_REFERENCED");
            assertEquals("/menus/source/noticeAction/denyActions/0/menuId", failure.details().getFirst().path());
            repo.update("source", saved.get("revision").asString(), menu("source")).join();
            repo.delete("target", target.get("revision").asString()).join();
            var invalid = failed(() -> repo.validate(source).join(), "VALIDATION_FAILED");
            assertEquals("/menu/noticeAction/denyActions/0/menuId", invalid.details().getFirst().path());
        }
    }

    @Test void externalFileChangesConflictAndBrokenStartupDoesNotPublishPartialCollection() throws Exception {
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var saved = repo.create(menu("welcome")).join();
            Files.writeString(directory.resolve("menus/welcome.json"), "# external edit\n" + Files.readString(directory.resolve("menus/welcome.json")));
            failed(() -> repo.update("welcome", saved.get("revision").asString(), menu("welcome")).join(), "YAML_SYNC_CONFLICT");
        }
        Files.writeString(directory.resolve("menus/broken.json"), "title: [broken");
        var registry = new MenuRegistry();
        try (var restart = new MenuRepository(new FileStorage(directory), validator(), registry, Clock.systemUTC())) {
            failed(() -> restart.initialize().join(), "MENU_SERVICE_UNAVAILABLE");
            assertFalse(restart.ready()); assertTrue(registry.snapshot().isEmpty());
        }
    }
}
