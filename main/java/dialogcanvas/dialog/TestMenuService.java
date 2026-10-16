package dialogcanvas.dialog;

import dialogcanvas.model.MenuDefinition;
import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.AtomicFiles;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An editable JSON example for manual server testing, separate from YAML menu storage. */
public final class TestMenuService implements AutoCloseable {
    public static final String MENU_ID = "dialogcanvas_test";
    public static final String ICON_MENU_ID = "dialogcanvas_icon_demo";
    public static final String SHOP_MENU_ID = "dialogcanvas_shop_demo";
    public static final String MAIN_MENU_ID = "dialogcanvas_menu_demo";
    public enum Example {
        MINIMAL("minimal-menu.json"), ICONS("icon-buttons.json"),
        SHOP("shop-demo.json"), MENU("menu-demo.json");
        private final String fileName;
        Example(String fileName) { this.fileName = fileName; }
    }
    private final Example example;
    private final Path file;
    private final JsonMapper mapper = MenuJson.createMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TestMenuService(Path pluginDirectory) {
        this(pluginDirectory, Example.MINIMAL);
    }

    public TestMenuService(Path pluginDirectory, Example example) {
        this.example = java.util.Objects.requireNonNull(example);
        file = pluginDirectory.resolve("examples").resolve(example.fileName);
    }

    public void initialize() throws IOException {
        AtomicFiles.createDirectories(file.getParent());
        AtomicFiles.requireTarget(file);
        if (!Files.exists(file)) {
            try (InputStream bundled = TestMenuService.class.getResourceAsStream("/examples/" + example.fileName)) {
                if (bundled == null) throw new IOException("Missing example resource: " + example.fileName);
                AtomicFiles.write(file, bundled.readAllBytes());
            }
        }
    }

    /** Read on each invocation so edits can be checked without restarting the server. */
    public CompletableFuture<MenuDefinition> load() {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    AtomicFiles.requireTarget(file);
                    return mapper.readValue(Files.readString(file), MenuDefinition.class);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }, executor);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
