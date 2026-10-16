package dialogcanvas.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Missing-only teaching files; never touches authoritative menus or synchronization state.
 * Called on the existing initialization worker, not the Minecraft main thread. */
public final class BundledExamples {
    public static final List<String> IDS = List.of("wiki_01_notice", "wiki_02_confirmation", "wiki_03_navigation",
            "wiki_04_inputs", "wiki_05_text_icons", "wiki_06_conditions", "wiki_07_commands",
            "wiki_08_shop", "wiki_09_limits", "wiki_10_player_data");

    public record Result(int created, int preserved, List<String> failed) {
        public Result { failed = List.copyOf(failed); }
    }

    @FunctionalInterface
    interface Create { void write(Path target, byte[] bytes) throws IOException; }

    private BundledExamples() {}

    public static Result install(Path pluginDirectory) throws IOException {
        return install(pluginDirectory, (path, bytes) -> AtomicFiles.writeIfUnchanged(path, bytes, null));
    }

    static Result install(Path pluginDirectory, Create create) throws IOException {
        // Read the complete fixed catalog first: a missing JAR resource must not silently
        // produce an incomplete "successful" installation. No user-controlled path input.
        var bundled = new LinkedHashMap<String, byte[]>();
        bundled.put("使用说明.md", resource("/examples/使用说明.md"));
        for (String id : IDS) bundled.put("menus/" + id + ".yml", resource("/examples/menus/" + id + ".yml"));
        Path root = pluginDirectory.toAbsolutePath().normalize().resolve("examples");
        AtomicFiles.createDirectories(root);
        AtomicFiles.createDirectories(root.resolve("menus"));
        int created = 0, preserved = 0;
        var failed = new ArrayList<String>();
        for (var entry : bundled.entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Example extraction interrupted");
            Path target = root.resolve(entry.getKey()).normalize();
            if (!target.startsWith(root)) throw new IOException("Invalid bundled example path");
            try {
                AtomicFiles.requireTarget(target);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { preserved++; continue; }
                // Recheck absence immediately before the atomic move, preserving any
                // manual file that appeared after the earlier existence check.
                create.write(target, entry.getValue());
                created++;
            } catch (IOException failure) {
                failed.add(entry.getKey()); // Fixed relative names only; no exception/path leakage.
            }
        }
        return new Result(created, preserved, failed);
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = BundledExamples.class.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing bundled teaching resource");
            byte[] bytes = stream.readNBytes(MenuYaml.MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MenuYaml.MAX_BYTES) throw new IOException("Invalid bundled teaching resource size");
            return bytes;
        }
    }
}
