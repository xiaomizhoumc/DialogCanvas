package dialogcanvas.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir Path directory;
    @Test void replacesExistingFileAndRemovesTemporaryFile() throws Exception {
        Path target = directory.resolve("player.yml"); Files.writeString(target, "old");
        AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void failedReplacementPreservesTargetAndCleansTemporaryFile() throws Exception {
        Path target = directory.resolve("player.yml"); Files.createDirectory(target);
        Files.writeString(target.resolve("original"), "preserved");
        assertThrows(IOException.class, () -> AtomicFiles.write(target, new byte[] {1, 2, 3}));
        assertEquals("preserved", Files.readString(target.resolve("original")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void unsupportedAtomicMoveHasNoFallbackAndCleansCompleteSiblingTemp() throws Exception {
        Path target = directory.resolve("state.json"); Files.writeString(target, "old");
        byte[] bytes = "complete new state".getBytes(StandardCharsets.UTF_8);
        assertThrows(AtomicMoveNotSupportedException.class, () -> AtomicFiles.write(target, bytes, (source, destination) -> {
            assertEquals(target.getParent(), source.getParent()); assertEquals(target, destination);
            assertArrayEquals(bytes, Files.readAllBytes(source));
            throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
        }));
        assertEquals("old", Files.readString(target));
        try (var entries = Files.list(directory)) { assertEquals(List.of(target), entries.toList()); }
    }
    @Test void redirectedAncestorIsRejectedBeforeCreatingFilesOrDirectories() throws Exception {
        Path outside = directory.resolve("outside"); Files.createDirectory(outside);
        Path link = directory.resolve("redirected"); PersistenceTestLinks.redirectDirectory(link, outside);
        assertThrows(IOException.class, () -> AtomicFiles.createDirectories(link.resolve("new-child")));
        assertThrows(IOException.class, () -> AtomicFiles.write(link.resolve("state.json"), new byte[]{1}));
        assertFalse(Files.exists(outside.resolve("new-child"))); assertFalse(Files.exists(outside.resolve("state.json")));
    }
    @Test void changedManualDocumentIsNotReplacedAndTempIsCleaned() throws Exception {
        Path target = directory.resolve("menu.yml"); byte[] baseline = "old".getBytes(StandardCharsets.UTF_8);
        Files.writeString(target, "manual change");
        assertThrows(IOException.class, () -> AtomicFiles.writeIfUnchanged(target, "export".getBytes(StandardCharsets.UTF_8), baseline));
        assertEquals("manual change", Files.readString(target));
        assertThrows(IOException.class, () -> AtomicFiles.writeIfUnchanged(target, baseline, null));
        assertEquals("manual change", Files.readString(target));
        try (var entries = Files.list(directory)) { assertEquals(List.of(target), entries.toList()); }
        AtomicFiles.writeIfUnchanged(target, baseline, Files.readAllBytes(target)); assertEquals("old", Files.readString(target));
    }
}
