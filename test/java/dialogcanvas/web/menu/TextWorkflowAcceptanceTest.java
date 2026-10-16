package dialogcanvas.web.menu;

import at.favre.lib.crypto.bcrypt.BCrypt;
import dialogcanvas.config.PluginConfig;
import dialogcanvas.dialog.TextRenderer;
import dialogcanvas.model.MenuJson;
import dialogcanvas.model.TextSpec;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.storage.MenuDocuments;
import dialogcanvas.storage.MenuYaml;
import dialogcanvas.web.EmbeddedHttpServer;
import dialogcanvas.web.auth.AuthService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real authenticated HTTP, production synchronization and fixed-version Adventure parsing. */
class TextWorkflowAcceptanceTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newHttpClient();
    private String origin, cookie, csrf;
    private HttpResponse<String> request(String method, String path, JsonNode body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).header("Origin", origin);
        if (cookie != null) builder.header("Cookie", cookie).header("X-CSRF-Token", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode node(HttpResponse<String> response) { return MenuJson.createMapper().readTree(response.body()); }
    private JsonNode envelope(JsonNode menu, String revision) {
        var value = MenuJson.createMapper().createObjectNode().set("menu", menu);
        if (revision != null) value.put("revision", revision);
        return value;
    }
    @Test void savesEveryTextFieldImportsManualColoursAndRestartsWithoutLoss() throws Exception {
        int port; try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        origin = "http://127.0.0.1:" + port;
        var config = new PluginConfig(port, "127.0.0.1", BCrypt.withDefaults().hashToString(4, "fixture-only".toCharArray()), root.resolve("data"));
        var json = MenuJson.createMapper();
        var fixture = json.readTree(Files.readString(Path.of("../frontend/src/components/__fixtures__/text-workflow-menu.json")));
        var formal = root.resolve("data/menus/text_workflow.json");
        var manual = root.resolve("menus/text_workflow.yml");
        JsonNode imported;
        try (var repository = new MenuRepository(new FileStorage(config.dataDirectory(), root.resolve("menus")), MenuRepositoryTest.validator());
             var server = new EmbeddedHttpServer(config, root, new AuthService(Clock.systemUTC(), 4), ignored -> fail("unexpected initial password"), repository)) {
            server.startAsync().join();
            var login = request("POST", "/api/auth/login", json.createObjectNode().put("password", "fixture-only"));
            assertEquals(200, login.statusCode()); cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0]; csrf = node(login).get("csrfToken").asString();
            var created = request("POST", "/api/menus", envelope(fixture, null));
            assertEquals(201, created.statusCode(), created.body());
            var saved = node(created);
            assertEquals("synced", saved.get("syncStatus").asString());
            assertDocuments(saved.get("menu"), formal, manual);
            assertFieldsAndNativeColours(saved.get("menu"));
            var changed = (tools.jackson.databind.node.ObjectNode) fixture.deepCopy();
            changed.set("title", json.readTree("{\"format\":\"minimessage\",\"value\":\"<gradient:#000000:#FFFFFF>ABCDE</gradient>\"}"));
            var response = request("PUT", "/api/menus/text_workflow", envelope(changed, saved.get("revision").asString()));
            assertEquals(200, response.statusCode(), response.body()); saved = node(response);
            assertDocuments(saved.get("menu"), formal, manual);
            byte[] before = Files.readAllBytes(formal);
            Files.writeString(manual, Files.readString(manual).replace("#000000:#FFFFFF", "#FF0000:#00FF00:#0000FF").replace("&lA&cB&rC", "&a确认"));
            var rejected = request("PUT", "/api/menus/text_workflow", envelope(changed, saved.get("revision").asString()));
            assertEquals(409, rejected.statusCode()); assertEquals("YAML_SYNC_CONFLICT", node(rejected).get("code").asString());
            assertArrayEquals(before, Files.readAllBytes(formal));
            repository.reloadYaml("text_workflow").join();
            imported = node(request("GET", "/api/menus/text_workflow", null));
            assertNotEquals(saved.get("revision"), imported.get("revision"));
            assertDocuments(imported.get("menu"), formal, manual);
            assertEquals(List.of("#FF0000", "#808000", "#00FF00", "#008080", "#0000FF"), colours(imported.get("menu").get("title")));
            assertEquals(List.of("#55FF55", "#55FF55"), colours(imported.get("menu").get("inputs").get(0).get("label")));
            assertEquals(fixture.get("actions").get(0).get("icon"), imported.get("menu").get("actions").get(0).get("icon"));
        }
        try (var restarted = new MenuRepository(new FileStorage(config.dataDirectory(), root.resolve("menus")), MenuRepositoryTest.validator())) {
            restarted.initialize().join();
            assertEquals(imported, restarted.get("text_workflow").join());
            assertDocuments(imported.get("menu"), formal, manual);
        }
    }
    private void assertDocuments(JsonNode expected, Path formal, Path manual) throws Exception {
        assertEquals(expected, MenuDocuments.decode(Files.readAllBytes(formal)));
        assertEquals(expected, MenuYaml.decode(Files.readAllBytes(manual)));
        assertFalse(expected.toString().contains("scopeId")); assertFalse(expected.toString().contains("gradientBreak"));
    }
    private void assertFieldsAndNativeColours(JsonNode menu) {
        assertEquals(Arrays.asList(null, "#000000", "#404040", "#808080", "#BFBFBF", "#FFFFFF", null), colours(menu.get("title")));
        assertEquals(List.of("#FF0000", "#808000", "#00FF00", "#008080", "#0000FF"), colours(menu.get("body").get(0).get("text")));
        assertEquals(Arrays.asList(null, "#FF5555", null), colours(menu.get("inputs").get(0).get("label")));
        assertEquals(List.of("#FF0000", "#996600", "#33CC00", "#00CC33", "#006699", "#0000FF"), colours(menu.get("actions").get(0).get("label")));
        assertEquals(List.of("#000000", "#404040", "#808080", "#BFBFBF", "#FFFFFF"), colours(menu.get("actions").get(0).get("tooltip")));
    }
    private List<String> colours(JsonNode text) {
        var colors = new ArrayList<String>(); collect(new TextRenderer().render(MenuJson.createMapper().treeToValue(text, TextSpec.class)), null, colors); return colors;
    }
    private void collect(Component component, TextColor inherited, List<String> colors) {
        var color = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent text) text.content().codePoints().forEach(ignored -> colors.add(color == null ? null : color.asHexString().toUpperCase(Locale.ROOT)));
        component.children().forEach(child -> collect(child, color, colors));
    }
}
