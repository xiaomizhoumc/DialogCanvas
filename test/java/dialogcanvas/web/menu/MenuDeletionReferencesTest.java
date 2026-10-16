package dialogcanvas.web.menu;

import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static dialogcanvas.web.menu.MenuRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the existing validator/referencePaths and real temporary files, not a second scanner. */
class MenuDeletionReferencesTest {
    @TempDir Path directory;

    private static ObjectNode button(String id) {
        var value = MenuJson.createMapper().createObjectNode(); value.put("id", id); value.put("label", "打开菜单"); return value;
    }
    private static ObjectNode source(String id, String kind, String slot, String branch, String target) {
        var document = (ObjectNode) menu(id); document.put("dialogType", kind);
        var action = button("open"); action.putArray(branch).addObject().put("type", "open_menu").put("menuId", target);
        if (kind.equals("confirmation")) {
            document.set("yesButton", button("yes")); document.set("noButton", button("no"));
        }
        if (kind.equals("multi_action")) {
            var buttons = document.putArray("actions");
            if (!slot.equals("actions/0")) buttons.add(button("base"));
        }
        if (slot.equals("actions/0")) document.withArray("actions").add(action);
        else document.set(slot, action);
        return document;
    }

    @ParameterizedTest @CsvSource({
            "notice,noticeAction,actions", "notice,noticeAction,denyActions",
            "confirmation,yesButton,actions", "confirmation,yesButton,denyActions",
            "confirmation,noButton,actions", "confirmation,noButton,denyActions",
            "multi_action,actions/0,actions", "multi_action,actions/0,denyActions",
            "multi_action,exitAction,actions", "multi_action,exitAction,denyActions"})
    void everySupportedButtonBranchReportsExactPathAndLeavesFilesUntouched(String kind, String slot, String branch) throws Exception {
        var files = new FileStorage(directory); var registry = new MenuRegistry();
        try (var repo = new MenuRepository(files, validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var target = repo.create(menu("target")).join();
            repo.create(source("source", kind, slot, branch, "target")).join();
            byte[] before = Files.readAllBytes(directory.resolve("menus/target.json")); var snapshot = registry.snapshot();
            var failure = failed(() -> repo.delete("target", target.get("revision").asString()).join(), "MENU_REFERENCED");
            assertEquals(409, failure.status());
            assertEquals(Set.of(new ApiException.Detail("/menus/source/" + slot + "/" + branch + "/0/menuId", "MENU_REFERENCE", "该菜单仍被引用")), Set.copyOf(failure.details()));
            assertEquals(snapshot, registry.snapshot()); assertArrayEquals(before, Files.readAllBytes(directory.resolve("menus/target.json")));
        }
    }

    @Test void multipleReferencersBothBranchesAndSelfReferenceKeepExistingRules() throws Exception {
        var registry = new MenuRegistry();
        try (var repo = new MenuRepository(new FileStorage(directory), validator(), registry, Clock.systemUTC())) {
            repo.initialize().join();
            var target = repo.create(source("target", "notice", "noticeAction", "actions", "target")).join();
            var first = source("first", "notice", "noticeAction", "actions", "target");
            ((ObjectNode) first.get("noticeAction")).putArray("denyActions").addObject().put("type", "open_menu").put("menuId", "target");
            var firstSaved = repo.create(first).join();
            var secondSaved = repo.create(source("second", "multi_action", "exitAction", "denyActions", "target")).join();
            var failure = failed(() -> repo.delete("target", target.get("revision").asString()).join(), "MENU_REFERENCED");
            assertEquals(Set.of("/menus/first/noticeAction/actions/0/menuId", "/menus/first/noticeAction/denyActions/0/menuId", "/menus/second/exitAction/denyActions/0/menuId"), failure.details().stream().map(ApiException.Detail::path).collect(Collectors.toSet()));
            assertEquals(3, failure.details().size());
            repo.update("first", firstSaved.get("revision").asString(), menu("first")).join();
            repo.update("second", secondSaved.get("revision").asString(), menu("second")).join();
            repo.delete("target", target.get("revision").asString()).join();
            assertFalse(registry.snapshot().containsKey("target"));
            assertFalse(Files.exists(directory.resolve("menus/target.json")));
            assertFalse(Files.exists(directory.resolve("manual-menus/target.yml")));
            assertFalse(Files.exists(directory.resolve("menu-sync/target.json")));
        }
    }

    @Test void arbitraryStringsAndMenuShapedPlayerDataDoNotCountAsReferences() {
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var target = repo.create(menu("target")).join();
            var document = (ObjectNode) menu("data_only"); document.put("title", "target");
            var action = button("data"); action.put("tooltip", "open_menu target"); document.set("noticeAction", action);
            var steps = action.putArray("actions");
            var data = steps.addObject(); data.put("type", "player_data_set"); data.put("key", "saved_menu");
            data.set("value", source("embedded", "notice", "noticeAction", "denyActions", "target"));
            var text = steps.addObject(); text.put("type", "message"); text.put("text", "target / open_menu / menuId");
            action.putArray("denyActions").addObject().put("type", "player_data_set").put("key", "menu_name").put("value", "target");
            var prepared = validator().prepare(document, Set.of("target"));
            assertTrue(prepared.references().isEmpty());
            assertTrue(MenuValidator.referencePaths(document, "/menus/data_only", "target").isEmpty());
            repo.create(document).join(); repo.delete("target", target.get("revision").asString()).join();
            assertEquals("target", repo.get("data_only").join().get("menu").get("title").asString());
        }
    }

    @Test void unsupportedNestedActionContainersAreRejectedRatherThanSilentlyTreatedAsExecutable() {
        var document = source("source", "notice", "noticeAction", "actions", "target");
        ((ObjectNode) document.get("noticeAction").get("actions").get(0)).putArray("actions").addObject().put("type", "open_menu").put("menuId", "nested");
        var failure = assertThrows(ApiException.class, () -> validator().prepare(document, Set.of("target", "nested")));
        assertEquals(422, failure.status()); assertEquals("INVALID_FIELD", failure.details().getFirst().code());
    }

    @Test void revisionAndManualYamlConflictsStillTakePrecedenceAndPreserveData() throws Exception {
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var target = repo.create(menu("target")).join();
            repo.create(source("source", "notice", "noticeAction", "actions", "target")).join();
            var stale = repo.update("target", target.get("revision").asString(), menu("target")).join();
            failed(() -> repo.delete("target", target.get("revision").asString()).join(), "REVISION_CONFLICT");
            var yaml = directory.resolve("manual-menus/target.yml");
            var edited = Files.readString(yaml) + "\n# pending owner edit\n"; Files.writeString(yaml, edited);
            var before = Files.readAllBytes(directory.resolve("menus/target.json"));
            failed(() -> repo.delete("target", stale.get("revision").asString()).join(), "YAML_SYNC_CONFLICT");
            assertEquals(edited, Files.readString(yaml)); assertArrayEquals(before, Files.readAllBytes(directory.resolve("menus/target.json")));
        }
    }

    @Test void queuedReferenceCreationAfterDeleteCheckCannotPublishDanglingReference() throws Exception {
        class GatedStore extends FaultStore {
            final CountDownLatch deleting = new CountDownLatch(1), release = new CountDownLatch(1);
            GatedStore() { super(directory); }
            @Override public boolean removeMenu(String id) throws IOException {
                if (id.equals("target")) {
                    deleting.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test gate"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                }
                return super.removeMenu(id);
            }
        }
        var files = new GatedStore(); var registry = new MenuRegistry();
        try (var repo = new MenuRepository(files, validator(), registry, Clock.systemUTC())) {
            repo.initialize().join(); var target = repo.create(menu("target")).join();
            var deletion = repo.delete("target", target.get("revision").asString());
            try {
                assertTrue(files.deleting.await(10, TimeUnit.SECONDS));
                var creation = repo.create(source("source", "notice", "noticeAction", "actions", "target"));
                assertFalse(creation.isDone(), "Collection writes must wait behind the deletion coordinator");
                files.release.countDown(); deletion.join();
                var failure = failed(creation::join, "VALIDATION_FAILED");
                assertEquals("MENU_REFERENCE_NOT_FOUND", failure.details().getFirst().code());
                assertFalse(registry.snapshot().containsKey("target")); assertFalse(registry.snapshot().containsKey("source"));
            } finally { files.release.countDown(); }
        }
    }

    @Test void referenceCreationQueuedBeforeDeleteBlocksDeletion() {
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var target = repo.create(menu("target")).join();
            var creation = repo.create(source("source", "notice", "noticeAction", "denyActions", "target"));
            var deletion = repo.delete("target", target.get("revision").asString());
            creation.join(); failed(deletion::join, "MENU_REFERENCED");
            assertEquals("target", repo.get("target").join().get("menu").get("id").asString());
        }
    }
}
