package dialogcanvas.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class BundledExamplesTest {
    @TempDir Path directory;

    @Test void freshInstallCopiesTenCompleteCommentedYamlFilesAndChineseGuideVerbatim() throws Exception {
        var result = BundledExamples.install(directory);
        assertEquals(11, result.created()); assertEquals(0, result.preserved()); assertTrue(result.failed().isEmpty());
        for (String id : BundledExamples.IDS) {
            byte[] bytes = Files.readAllBytes(directory.resolve("examples/menus/" + id + ".yml"));
            try (var resource = BundledExamples.class.getResourceAsStream("/examples/menus/" + id + ".yml")) {
                assertNotNull(resource); assertArrayEquals(resource.readAllBytes(), bytes);
            }
            assertEquals(id, MenuYaml.decode(bytes).path("id").asString());
            String annotated = new String(bytes, StandardCharsets.UTF_8);
            for (String key : java.util.List.of("schemaVersion", "id", "title", "dialogType"))
                assertTrue(annotated.contains("# " + key + "："), id + " missing field guidance: " + key);
            assertArrayEquals(Files.readAllBytes(Path.of("../examples/wiki/menus/" + id + ".yml")), bytes);
        }
        assertTrue(Files.readString(directory.resolve("examples/使用说明.md")).contains("不会自动加载"));
        assertFalse(Files.exists(directory.resolve("menus"))); assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test void restartPreservesEditedExamplesGuideAndUnrelatedUserFilesAndRepairsOnlyMissingFile() throws Exception {
        BundledExamples.install(directory);
        Path edited = directory.resolve("examples/menus/wiki_08_shop.yml"), guide = directory.resolve("examples/使用说明.md");
        Files.writeString(edited, "# 服主手写，不要覆盖\n"); Files.writeString(guide, "服主的说明\n");
        Path config = directory.resolve("config.yml"); Files.writeString(config, "user configuration");
        var before = new HashMap<Path, byte[]>();
        try (var files = Files.walk(directory)) { for (Path p : files.filter(Files::isRegularFile).toList()) before.put(p, Files.readAllBytes(p)); }
        var repeated = BundledExamples.install(directory);
        assertEquals(0, repeated.created()); assertEquals(11, repeated.preserved()); assertTrue(repeated.failed().isEmpty());
        for (var entry : before.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        Files.delete(directory.resolve("examples/menus/wiki_01_notice.yml"));
        var repaired = BundledExamples.install(directory);
        assertEquals(1, repaired.created()); assertEquals(10, repaired.preserved()); assertTrue(repaired.failed().isEmpty());
        assertEquals("# 服主手写，不要覆盖\n", Files.readString(edited));
    }

    @Test void writeFailureIsReportedAndRestartCanCompleteWithoutOverwritingSuccesses() throws Exception {
        var result = BundledExamples.install(directory, (path, bytes) -> {
            if (path.getFileName().toString().equals("wiki_08_shop.yml")) throw new IOException("injected secret absolute path must not be reported");
            AtomicFiles.writeIfUnchanged(path, bytes, null);
        });
        assertEquals(10, result.created()); assertEquals(java.util.List.of("menus/wiki_08_shop.yml"), result.failed());
        assertFalse(result.toString().contains("secret"));
        var retry = BundledExamples.install(directory);
        assertEquals(1, retry.created()); assertEquals(10, retry.preserved()); assertTrue(retry.failed().isEmpty());
        try (var paths = Files.walk(directory)) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".dcmenu-"))); }
    }

    @Test void fileAppearingDuringCreationIsNeverOverwritten() throws Exception {
        var result = BundledExamples.install(directory, (path, bytes) -> {
            if (path.getFileName().toString().equals("wiki_08_shop.yml")) Files.writeString(path, "new manual file", StandardOpenOption.CREATE_NEW);
            AtomicFiles.writeIfUnchanged(path, bytes, null);
        });
        assertEquals(java.util.List.of("menus/wiki_08_shop.yml"), result.failed());
        assertEquals("new manual file", Files.readString(directory.resolve("examples/menus/wiki_08_shop.yml")));
    }

    @Test void redirectedExampleDirectoryCannotCreateFilesOutsidePluginRoot() throws Exception {
        Path plugin = directory.resolve("plugin"), outside = directory.resolve("outside");
        Files.createDirectories(plugin); Files.createDirectories(outside);
        PersistenceTestLinks.redirectDirectory(plugin.resolve("examples"), outside);
        assertThrows(IOException.class, () -> BundledExamples.install(plugin));
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }
}
