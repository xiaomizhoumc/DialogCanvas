package dialogcanvas.storage;

import dialogcanvas.model.MenuJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FileMenuStorageTest {
    @TempDir Path directory;
    @Test void preservesJsonScalarTypesThroughYamlAndDeletesAtomically() throws Exception {
        try (var files = new FileStorage(directory)) {
            files.initialize();
            var document = MenuJson.createMapper().readTree("{\"id\":\"typed\",\"number\":3,\"text\":\"1000\",\"flag\":true,\"empty\":null,\"values\":[\"true\",\"$(amount)\",\"2026-10-11T00:00:00Z\"]}");
            files.save(StorageCategory.MENU, "typed", document);
            assertEquals(document, files.load(StorageCategory.MENU, "typed").orElseThrow());
            assertEquals(java.util.List.of("typed"), files.listIds(StorageCategory.MENU));
            assertTrue(files.delete(StorageCategory.MENU, "typed")); assertFalse(files.exists(StorageCategory.MENU, "typed"));
            assertFalse(files.delete(StorageCategory.MENU, "typed"));
            assertFalse(Files.list(directory.resolve("menus")).anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
    @Test void rejectsTraversalAndDirectoryTargets() throws Exception {
        try (var files = new FileStorage(directory)) {
            files.initialize(); assertThrows(IllegalArgumentException.class, () -> files.readMenuBytes("../escape"));
            Files.createDirectory(directory.resolve("menus/trap.json"));
            assertThrows(java.io.IOException.class, () -> files.writeMenuBytes("trap", new byte[0]));
        }
    }
    @Test void unsafeYamlIsRejectedWithoutDeserializingObjects() {
        for (String yaml : java.util.List.of("id: first\nid: second", "!!java.util.Date {}", "x: &x [*x]", "date: 2026-10-11")) {
            assertThrows(RuntimeException.class, () -> MenuYaml.decode(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
