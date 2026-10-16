package dialogcanvas.engine.production;

import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ProductionStorePersistenceTest {
    @TempDir Path directory;
    @Test void failedPlayerWriteKeepsFileAndCacheAndOversizeCannotBecomeUnreadableState() throws Exception {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        UUID player = UUID.randomUUID(); var json = MenuJson.createMapper();
        try (var store = new ProductionStore(directory, (path, bytes) -> {
            if (fail.get() && path.getParent().getFileName().toString().equals("player-data")) throw new IOException("injected");
            AtomicFiles.write(path, bytes);
        })) {
            store.initialize().join(); store.begin(player, json.createObjectNode(), "button", Map.of()).join();
            store.set(player, "key", json.readTree("1")).join();
            Path file = directory.resolve("player-data/" + player + ".json"); byte[] before = Files.readAllBytes(file);
            fail.set(true); assertThrows(CompletionException.class, () -> store.increment(player, "key", 2).join());
            assertArrayEquals(before, Files.readAllBytes(file)); assertEquals(1, store.find(player, "key").orElseThrow().asInt());
            fail.set(false);
            assertThrows(CompletionException.class, () -> store.set(player, "huge", json.getNodeFactory().stringNode("x".repeat(4 * 1024 * 1024))).join());
            assertArrayEquals(before, Files.readAllBytes(file)); assertTrue(store.find(player, "huge").isEmpty());
        }
    }
    @Test void redirectedRootDoesNotCreateBusinessDirectoriesOutsideFixtureRoot() throws Exception {
        Path outside = directory.resolve("outside"); Files.createDirectory(outside);
        Path redirect = directory.resolve("redirect"); PersistenceTestLinks.redirectDirectory(redirect, outside);
        try (var store = new ProductionStore(redirect.resolve("data"))) {
            assertThrows(CompletionException.class, () -> store.initialize().join());
            assertFalse(Files.exists(outside.resolve("data")));
        }
    }
    @Test void failedInitialIntentCannotStartBusinessOrBlockPlayer() {
        UUID id = UUID.randomUUID();
        try (var store = new ProductionStore(directory, (path, bytes) -> { throw new IOException("disk full"); })) {
            store.initialize().join();
            assertThrows(CompletionException.class, () -> store.begin(id, MenuJson.createMapper().createObjectNode(), "button", Map.of()).join());
            assertFalse(store.blocked(id));
        }
    }
}
