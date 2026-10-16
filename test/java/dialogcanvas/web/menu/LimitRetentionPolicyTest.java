package dialogcanvas.web.menu;

import dialogcanvas.engine.production.ProductionActionEngine;
import dialogcanvas.engine.production.ProductionStore;
import dialogcanvas.model.LimitDefinition;
import dialogcanvas.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dialogcanvas.web.menu.MenuRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LimitRetentionPolicyTest {
    @TempDir Path directory;
    private ObjectNode definition(String mode) {
        var menu = (ObjectNode) menu("shop"); var button = menu.putObject("noticeAction");
        button.put("id", "buy").put("label", "购买");
        var cap = button.putObject("limit").putObject("buy"); cap.put("player", 3).put("reset", mode);
        if (mode.equals("TIMED")) cap.put("timedSeconds", 60);
        return menu;
    }
    private String key() { return ProductionActionEngine.limitKey("shop", "buy", "buy", null); }
    @Test void verifiesCurrentPolicyAndRejectsChangedPermanentAndOrphanedRules() {
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var initial = repo.create(definition("TIMED")).join(); var ran = new AtomicBoolean();
            repo.protectLimitRetention(Map.of(key(), "TIMED"), () -> {
                assertTrue(Thread.currentThread().getName().contains("DCMenu-menus")); ran.set(true);
            }).join(); assertTrue(ran.get()); ran.set(false);
            var permanent = repo.update("shop", initial.get("revision").asString(), definition("NEVER")).join();
            var error = assertThrows(CompletionException.class, () -> repo.protectLimitRetention(Map.of(key(), "TIMED"), () -> ran.set(true)).join());
            assertInstanceOf(ProductionStore.MaintenanceDenied.class, error.getCause()); assertFalse(ran.get());
            repo.delete("shop", permanent.get("revision").asString()).join();
            assertThrows(CompletionException.class, () -> repo.protectLimitRetention(Map.of(key(), "TIMED"), () -> ran.set(true)).join()); assertFalse(ran.get());
        }
    }
    @Test void sharedScopeWithInconsistentResetTypesIsNotConsideredExpired() {
        var menu = definition("TIMED"); menu.put("dialogType", "confirmation");
        var yes = menu.remove("noticeAction"); menu.set("yesButton", yes);
        var no = yes.deepCopy(); ((ObjectNode) no).put("id", "other"); menu.set("noButton", no);
        ((ObjectNode) yes.get("limit").get("buy")).put("scope", "shared");
        ((ObjectNode) no.get("limit").get("buy")).put("scope", "shared").put("reset", "NEVER").remove("timedSeconds");
        var cap = new LimitDefinition(); cap.setScope("shared"); String key = ProductionActionEngine.limitKey("shop", "buy", "buy", cap);
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); repo.create(menu).join();
            assertThrows(CompletionException.class, () -> repo.protectLimitRetention(Map.of(key, "TIMED"), () -> fail("ambiguous rule must not clean")).join());
        }
    }
    @Test void menuUpdateCannotPublishBetweenRuleCheckAndPlayerFileReplacement() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var repo = new MenuRepository(new FileStorage(directory), validator())) {
            repo.initialize().join(); var initial = repo.create(definition("TIMED")).join();
            var guard = repo.protectLimitRetention(Map.of(key(), "TIMED"), () -> {
                entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout");
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var update = repo.update("shop", initial.get("revision").asString(), definition("NEVER")); assertFalse(update.isDone());
                release.countDown(); guard.get(5, TimeUnit.SECONDS); update.get(5, TimeUnit.SECONDS);
                assertThrows(CompletionException.class, () -> repo.protectLimitRetention(Map.of(key(), "TIMED"), () -> fail("policy has changed")).join());
            } finally { release.countDown(); }
        }
    }
}
