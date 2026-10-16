package dialogcanvas.web.menu;

import dialogcanvas.audit.AuditLogger;
import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class MenuAuditTest {
    @TempDir Path root;
    List<JsonNode> rows(AuditLogger audit) throws Exception {
        audit.closeAsync().get(5, TimeUnit.SECONDS);
        return Files.readAllLines(root.resolve("logs/audit.log")).stream().map(MenuJson.createMapper()::readTree).toList();
    }
    @Test void actualCrudAndRevisionConflictAreRecordedButReadsAndValidationAreNot() throws Exception {
        var audit = new AuditLogger(root, Logger.getAnonymousLogger());
        var repo = new MenuRepository(new FileStorage(root.resolve("data")), MenuRepositoryTest.validator()); repo.auditLogger(audit);
        try {
            repo.initialize().join(); var menu = (tools.jackson.databind.node.ObjectNode) MenuRepositoryTest.menu("shop"); menu.put("title", "私密正文\nCookie CSRF private-body");
            var created = repo.create(menu, AuditLogger.Origin.web()).join();
            repo.list().join(); repo.get("shop").join(); repo.validate(menu).join();
            var changed = repo.update("shop", created.path("revision").asString(), menu, AuditLogger.Origin.web()).join();
            assertThrows(CompletionException.class, () -> repo.update("shop", created.path("revision").asString(), menu, AuditLogger.Origin.web()).join());
            repo.delete("shop", changed.path("revision").asString(), AuditLogger.Origin.web()).join(); repo.closeAsync().join();
            var rows = rows(audit); assertEquals(4, rows.size());
            assertEquals(List.of("MENU_CREATE", "MENU_UPDATE", "MENU_UPDATE", "MENU_DELETE"), rows.stream().map(row -> row.path("eventType").asString()).toList());
            assertTrue(rows.getFirst().path("beforeRevision").isNull()); assertEquals(created.path("revision"), rows.getFirst().path("afterRevision"));
            assertEquals(created.path("revision"), rows.get(1).path("beforeRevision")); assertEquals(changed.path("revision"), rows.get(1).path("afterRevision"));
            assertEquals("REVISION_CONFLICT", rows.get(2).path("code").asString()); assertEquals("FAILED", rows.get(2).path("result").asString());
            assertTrue(rows.getLast().path("afterRevision").isNull()); assertEquals(changed.path("revision"), rows.getLast().path("beforeRevision"));
            String raw = Files.readString(root.resolve("logs/audit.log")); assertFalse(raw.contains("private-body")); assertFalse(raw.contains("Cookie")); assertFalse(raw.contains("私密"));
        } finally { repo.closeAsync().join(); audit.closeAsync().join(); }
    }
    @Test void formalCommitWithFailedYamlExportIsPartialNotFailedOrRolledBack() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(root.resolve("data")) {
            @Override public void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException {
                if (kind == Auxiliary.YAML) throw new IOException("private-data"); super.writeAuxiliary(kind, id, bytes);
            }
        };
        var audit = new AuditLogger(root, Logger.getAnonymousLogger()); var repo = new MenuRepository(files, MenuRepositoryTest.validator()); repo.auditLogger(audit);
        try {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("shop"), AuditLogger.Origin.web()).join();
            assertTrue(repo.definition("shop").join().getId().equals("shop")); assertTrue(Files.exists(root.resolve("data/menus/shop.json"))); repo.closeAsync().join();
            var record = rows(audit).getFirst(); assertEquals("PARTIAL", record.path("result").asString()); assertEquals("YAML_SYNC_FAILED", record.path("code").asString()); assertEquals(saved.path("revision"), record.path("afterRevision"));
        } finally { repo.closeAsync().join(); audit.closeAsync().join(); }
    }
    @Test void failedPublicationAndCompensationUncertaintyHaveDifferentActualResults() throws Exception {
        var files = new MenuRepositoryTest.FaultStore(root.resolve("data")); var registry = new MenuRepositoryTest.FaultRegistry();
        var audit = new AuditLogger(root, Logger.getAnonymousLogger()); var repo = new MenuRepository(files, MenuRepositoryTest.validator(), registry, Clock.systemUTC()); repo.auditLogger(audit);
        try {
            repo.initialize().join(); var menu = MenuRepositoryTest.menu("shop"); var saved = repo.create(menu).join();
            registry.failNext = true; assertThrows(CompletionException.class, () -> repo.update("shop", saved.path("revision").asString(), menu).join());
            registry.failNext = true; files.failWriteAt = 2; assertThrows(CompletionException.class, () -> repo.update("shop", saved.path("revision").asString(), menu).join());
            repo.closeAsync().join(); var rows = rows(audit); assertEquals("REGISTRY_PUBLISH_FAILED", rows.get(1).path("code").asString());
            assertEquals("FAILED", rows.get(1).path("result").asString()); assertEquals("UNCERTAIN", rows.get(2).path("result").asString()); assertEquals("MENU_STATE_UNCERTAIN", rows.get(2).path("code").asString());
        } finally { repo.closeAsync().join(); audit.closeAsync().join(); }
    }
    @Test void batchImportsAuditEachItemAndPartialSummaryWithoutRecordingYamlBody() throws Exception {
        var audit = new AuditLogger(root, Logger.getAnonymousLogger()); var repo = new MenuRepository(new FileStorage(root.resolve("data")), MenuRepositoryTest.validator()); repo.auditLogger(audit);
        try {
            repo.initialize().join(); Path manual = root.resolve("data/manual-menus");
            Files.writeString(manual.resolve("good.yml"), "schemaVersion: 1\nid: good\ntitle: private-yaml-body\ndialogType: notice\n");
            Files.writeString(manual.resolve("bad.yml"), "id: bad\ntitle: private-yaml-error\ndialogType: unsupported\n");
            var result = repo.reloadAllYaml(new AuditLogger.Origin("console", "console")).join(); assertEquals(2, result.path("items").size());
            repo.closeAsync().join(); var rows = rows(audit); assertEquals(3, rows.size());
            assertEquals(2, rows.stream().filter(row -> row.path("eventType").asString().equals("YAML_IMPORT")).count());
            assertEquals("PARTIAL", rows.getLast().path("result").asString()); assertEquals("BATCH_PARTIAL", rows.getLast().path("code").asString());
            assertFalse(Files.readString(root.resolve("logs/audit.log")).contains("private-yaml"));
        } finally { repo.closeAsync().join(); audit.closeAsync().join(); }
    }
    @Test void auditDiskUnavailableDoesNotChangeSuccessfulBusinessOrInviteWriteRetry() throws Exception {
        Files.writeString(root.resolve("logs"), "blocked-directory"); var audit = new AuditLogger(root, Logger.getAnonymousLogger());
        var repo = new MenuRepository(new FileStorage(root.resolve("data")), MenuRepositoryTest.validator()); repo.auditLogger(audit);
        try {
            repo.initialize().join(); var saved = repo.create(MenuRepositoryTest.menu("shop"), AuditLogger.Origin.web()).join();
            assertNotNull(saved.get("revision")); assertEquals("shop", repo.definition("shop").join().getId()); repo.closeAsync().join(); audit.closeAsync().get(5, TimeUnit.SECONDS);
            assertEquals(1, audit.stats().failed()); assertFalse(audit.stats().healthy()); assertEquals("blocked-directory", Files.readString(root.resolve("logs")));
        } finally { repo.closeAsync().join(); audit.closeAsync().join(); }
    }
    @Test void cancelledCallerCannotSuppressActualCommittedMenuAudit() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var files = new MenuRepositoryTest.FaultStore(root.resolve("data")) {
            @Override public void writeMenuBytes(String id, byte[] bytes) throws IOException {
                entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IOException(e); } super.writeMenuBytes(id, bytes);
            }
        };
        var audit = new AuditLogger(root, Logger.getAnonymousLogger()); var repo = new MenuRepository(files, MenuRepositoryTest.validator()); repo.auditLogger(audit);
        try {
            repo.initialize().join(); var work = repo.create(MenuRepositoryTest.menu("shop")); assertTrue(entered.await(5, TimeUnit.SECONDS)); work.cancel(false); release.countDown();
            assertEquals("shop", repo.definition("shop").join().getId()); repo.closeAsync().join(); var rows = rows(audit);
            assertEquals(1, rows.size()); assertEquals("SUCCESS", rows.getFirst().path("result").asString());
        } finally { release.countDown(); repo.closeAsync().join(); audit.closeAsync().join(); }
    }
}
