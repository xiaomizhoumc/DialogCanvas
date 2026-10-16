package dialogcanvas.web.menu;

import dialogcanvas.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

class MenuSynchronizationTest {
    @TempDir Path directory;
    MenuRepository repository(MenuPersistence files) { return new MenuRepository(files, MenuRepositoryTest.validator()); }
    Path formal() { return directory.resolve("menus/welcome.json"); }
    Path manual() { return directory.resolve("manual-menus/welcome.yml"); }
    @Test void jsonYamlRoundTripPendingImportAndConflictPreserveFiles() throws Exception {
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            assertEquals(saved.get("menu"), MenuDocuments.decode(Files.readAllBytes(formal())));
            assertEquals(saved.get("menu"), MenuYaml.decode(Files.readAllBytes(manual())));
            String text = Files.readString(manual()).replace("&6欢迎", "手动标题"); Files.writeString(manual(), text);
            byte[] before = Files.readAllBytes(formal());
            assertEquals("pendingImport", repo.get("welcome").join().get("syncStatus").asString());
            MenuRepositoryTest.failed(() -> repo.update("welcome", saved.get("revision").asString(), saved.get("menu")).join(), "YAML_SYNC_CONFLICT");
            MenuRepositoryTest.failed(() -> repo.delete("welcome", saved.get("revision").asString()).join(), "YAML_SYNC_CONFLICT");
            assertArrayEquals(before, Files.readAllBytes(formal())); assertEquals(text, Files.readString(manual()));
            var imported = repo.reloadYaml("welcome").join();
            assertEquals("手动标题", imported.get("menu").get("title").asString());
            assertEquals("synced", imported.get("syncStatus").asString());
            MenuRepositoryTest.failed(() -> repo.update("welcome", saved.get("revision").asString(), imported.get("menu")).join(), "REVISION_CONFLICT");
            repo.delete("welcome", imported.get("revision").asString()).join();
            assertFalse(Files.exists(formal())); assertFalse(Files.exists(manual()));
            assertFalse(Files.exists(directory.resolve("menu-sync/welcome.json")));
        }
    }
    @Test void exportFailureCommitsFormalDataAndRecoversOnRestart() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(directory) {
            boolean fail = true;
            @Override public void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException {
                if (kind == Auxiliary.YAML && fail) { fail = false; throw new IOException("export denied"); }
                super.writeAuxiliary(kind, id, bytes);
            }
        };
        String revision;
        try (var repo = repository(files)) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            revision = saved.get("revision").asString();
            assertEquals("YAML_SYNC_FAILED", saved.get("warnings").get(0).get("code").asString());
            assertEquals(saved.get("menu"), MenuDocuments.decode(Files.readAllBytes(formal())));
        }
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); var restored = repo.get("welcome").join();
            assertEquals(revision, restored.get("revision").asString()); assertEquals("synced", restored.get("syncStatus").asString());
            assertEquals(restored.get("menu"), MenuYaml.decode(Files.readAllBytes(manual())));
        }
    }
    @Test void yamlEditedDuringFormalCommitIsPreserved() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(directory) {
            @Override public void writeMenuBytes(String id, byte[] bytes) throws IOException {
                super.writeMenuBytes(id, bytes);
                if (Files.exists(manual())) Files.writeString(manual(), "# concurrent manual edit\n" + Files.readString(manual()));
            }
        };
        try (var repo = repository(files)) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            var changed = repo.update("welcome", saved.get("revision").asString(), saved.get("menu")).join();
            assertEquals("YAML_SYNC_FAILED", changed.get("warnings").get(0).get("code").asString());
            assertTrue(Files.readString(manual()).startsWith("# concurrent manual edit"));
        }
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); assertTrue(Files.readString(manual()).startsWith("# concurrent manual edit"));
            assertEquals("syncFailed", repo.get("welcome").join().get("syncStatus").asString());
        }
    }
    @Test void missingBaselineAndBothEditedDoNotGuessOrOverwrite() throws Exception {
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            Files.writeString(formal(), Files.readString(formal()) + "\n"); Files.writeString(manual(), "# edited\n" + Files.readString(manual()));
            MenuRepositoryTest.failed(() -> repo.reloadYaml("welcome").join(), "YAML_SYNC_CONFLICT");
            Files.delete(directory.resolve("menu-sync/welcome.json"));
            MenuRepositoryTest.failed(() -> repo.reloadYaml("welcome").join(), "YAML_SYNC_CONFLICT");
            MenuRepositoryTest.failed(() -> repo.update("welcome", saved.get("revision").asString(), saved.get("menu")).join(), "YAML_SYNC_CONFLICT");
        }
    }
    @Test void legacyMigrationBacksUpAndRunsOnceWithCollisionProtection() throws Exception {
        Files.createDirectories(directory.resolve("menus"));
        byte[] legacy = MenuYaml.encode(MenuRepositoryTest.menu("welcome")); Files.write(directory.resolve("menus/welcome.yml"), legacy);
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); assertTrue(Files.exists(formal()));
            assertArrayEquals(legacy, Files.readAllBytes(directory.resolve("menu-migration-backup/welcome.yml")));
        }
        try (var repo = repository(new FileStorage(directory))) { repo.initialize().join(); assertTrue(repo.ready()); }
        Files.delete(directory.resolve("menu-migration-receipts/welcome.json"));
        Files.writeString(formal(), "{\"id\":\"collision\"}");
        try (var repo = repository(new FileStorage(directory))) {
            MenuRepositoryTest.failed(() -> repo.initialize().join(), "MENU_SERVICE_UNAVAILABLE");
            assertEquals("{\"id\":\"collision\"}", Files.readString(formal()));
        }
    }
    @Test void batchImportReportsPartialResultsAndKeepsInvalidPreviousSnapshot() throws Exception {
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            Files.writeString(manual(), "title: [broken");
            Files.write(directory.resolve("manual-menus/other.yml"), MenuYaml.encode(MenuRepositoryTest.menu("other")));
            var result = repo.reloadAllYaml().join();
            assertEquals(2, result.get("items").size()); assertEquals(saved.get("menu"), repo.get("welcome").join().get("menu"));
            assertNotNull(repo.get("other").join()); assertTrue(Files.readString(manual()).contains("[broken"));
        }
    }
    @Test void manualEditDuringDeleteCancelsDeleteAndCompensationPreservesNewYaml() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(directory) {
            @Override public boolean removeMenu(String id) throws IOException {
                boolean removed = super.removeMenu(id);
                Files.writeString(manual(), "# edited during delete\n" + Files.readString(manual()));
                return removed;
            }
        };
        try (var repo = repository(files)) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            byte[] json = Files.readAllBytes(formal());
            MenuRepositoryTest.failed(() -> repo.delete("welcome", saved.get("revision").asString()).join(), "YAML_SYNC_CONFLICT");
            assertArrayEquals(json, Files.readAllBytes(formal()));
            assertTrue(Files.readString(manual()).startsWith("# edited during delete"));
            assertEquals("pendingImport", repo.get("welcome").join().get("syncStatus").asString());
        }
    }
    @Test void publicationFailureAfterDeleteDoesNotOverwriteNewManualFile() throws Exception {
        var registry = new MenuRegistry() {
            boolean armed;
            @Override public void publish(Map<String, Entry> next) {
                super.publish(next);
                if (!next.isEmpty()) armed = true;
                else if (armed) {
                    armed = false;
                    try { Files.writeString(manual(), "# new manual file\nid: welcome\n"); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    throw new IllegalStateException("publish failed after external edit");
                }
            }
        };
        try (var repo = new MenuRepository(new FileStorage(directory), MenuRepositoryTest.validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            MenuRepositoryTest.failed(() -> repo.delete("welcome", saved.get("revision").asString()).join(), "STORAGE_DELETE_FAILED");
            assertEquals("# new manual file\nid: welcome\n", Files.readString(manual()));
            assertEquals(saved.get("menu"), repo.get("welcome").join().get("menu"));
            assertEquals("pendingImport", repo.get("welcome").join().get("syncStatus").asString());
        }
    }
    @Test void interruptedMigrationCannotReplaceOriginalBackupAfterLegacyEdit() throws Exception {
        Files.createDirectories(directory.resolve("menus"));
        byte[] original = MenuYaml.encode(MenuRepositoryTest.menu("welcome"));
        Files.write(directory.resolve("menus/welcome.yml"), original);
        var files = new MenuRepositoryTest.FaultStore(directory); files.failWriteAt = 1;
        try (var repo = repository(files)) { MenuRepositoryTest.failed(() -> repo.initialize().join(), "MENU_SERVICE_UNAVAILABLE"); }
        Files.writeString(directory.resolve("menus/welcome.yml"), "# changed after backup\n" + new String(original, java.nio.charset.StandardCharsets.UTF_8));
        try (var repo = repository(new FileStorage(directory))) {
            MenuRepositoryTest.failed(() -> repo.initialize().join(), "MENU_SERVICE_UNAVAILABLE");
            assertFalse(Files.exists(formal()));
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("menu-migration-backup/welcome.yml")));
        }
    }
    @Test void receiptWithoutFormalJsonDoesNotResurrectDeletedMigratedMenu() throws Exception {
        Files.createDirectories(directory.resolve("menus"));
        Files.write(directory.resolve("menus/welcome.yml"), MenuYaml.encode(MenuRepositoryTest.menu("welcome")));
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); repo.delete("welcome", repo.get("welcome").join().get("revision").asString()).join();
        }
        try (var repo = repository(new FileStorage(directory))) { repo.initialize().join(); assertTrue(repo.ready()); assertTrue(repo.completionIds().isEmpty()); }
        assertFalse(Files.exists(formal()));
    }
    @Test void finalBaselineFailurePreservesLaterEditAcrossRestartAndRetry() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(directory) {
            int syncWrites;
            @Override public void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException {
                if (kind == Auxiliary.SYNC && ++syncWrites == 2) throw new IOException("final baseline failure");
                super.writeAuxiliary(kind, id, bytes);
            }
        };
        try (var repo = repository(files)) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            assertEquals("YAML_SYNC_FAILED", saved.get("warnings").get(0).get("code").asString());
            assertEquals(saved.get("menu"), MenuYaml.decode(Files.readAllBytes(manual())));
        }
        String edited = "# edit after failed baseline\n" + Files.readString(manual()); Files.writeString(manual(), edited);
        for (int attempt = 0; attempt < 2; attempt++) try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); var saved = repo.get("welcome").join();
            assertEquals("syncFailed", saved.get("syncStatus").asString());
            MenuRepositoryTest.failed(() -> repo.update("welcome", saved.get("revision").asString(), saved.get("menu")).join(), "YAML_SYNC_CONFLICT");
            assertEquals(edited, Files.readString(manual()));
        }
    }
    @Test void exportIntentFailureReturnsPartialSuccessAndDoesNotGuessOnRestart() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(directory) {
            int writes;
            @Override public void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException {
                if (kind == Auxiliary.SYNC && ++writes == 3) throw new IOException("intent failure");
                super.writeAuxiliary(kind, id, bytes);
            }
        };
        byte[] previousYaml;
        try (var repo = repository(files)) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join();
            previousYaml = Files.readAllBytes(manual());
            var changed = repo.update("welcome", saved.get("revision").asString(), saved.get("menu")).join();
            assertEquals("YAML_SYNC_FAILED", changed.get("warnings").get(0).get("code").asString());
            assertEquals(changed.get("menu"), MenuDocuments.decode(Files.readAllBytes(formal())));
            assertEquals(changed.get("menu"), repo.get("welcome").join().get("menu"));
        }
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); assertEquals("syncFailed", repo.get("welcome").join().get("syncStatus").asString());
            assertArrayEquals(previousYaml, Files.readAllBytes(manual()));
        }
    }
    @Test void finalBaselineFailureWithoutManualEditCompletesOnRestart() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(directory) {
            int writes;
            @Override public void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException {
                if (kind == Auxiliary.SYNC && ++writes == 2) throw new IOException("baseline failure");
                super.writeAuxiliary(kind, id, bytes);
            }
        };
        String revision;
        try (var repo = repository(files)) {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("welcome")).join(); revision = saved.get("revision").asString();
            assertEquals("YAML_SYNC_FAILED", saved.get("warnings").get(0).get("code").asString());
        }
        try (var repo = repository(new FileStorage(directory))) {
            repo.initialize().join(); var saved = repo.get("welcome").join();
            assertEquals("synced", saved.get("syncStatus").asString()); assertEquals(revision, saved.get("revision").asString());
        }
    }
}
