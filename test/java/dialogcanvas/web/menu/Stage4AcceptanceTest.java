package dialogcanvas.web.menu;

import at.favre.lib.crypto.bcrypt.BCrypt;
import dialogcanvas.config.PluginConfig;
import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.storage.MenuYaml;
import dialogcanvas.web.EmbeddedHttpServer;
import dialogcanvas.web.auth.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end checks use real HTTP and production file storage, including service recreation. */
class Stage4AcceptanceTest {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private EmbeddedHttpServer server;
    private MenuRegistry registry;
    private PluginConfig config;
    private String origin, cookie, csrf;

    @BeforeEach void start() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        config = new PluginConfig(port, "127.0.0.1", BCrypt.withDefaults().hashToString(4, "acceptance-password".toCharArray()), directory.resolve("data"));
        origin = "http://127.0.0.1:" + port;
        startService(); login();
    }

    private void startService() {
        registry = new MenuRegistry();
        var menus = new MenuRepository(new FileStorage(config.dataDirectory()), MenuRepositoryTest.validator(), registry, Clock.systemUTC());
        server = new EmbeddedHttpServer(config, directory, new AuthService(Clock.systemUTC(), 4), ignored -> fail(), menus);
        server.startAsync().join();
    }

    @AfterEach void stop() { if (server != null) server.close(); }

    private HttpRequest request(String method, String path, JsonNode body, String sessionCookie, String sessionCsrf) {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10)).header("Origin", origin);
        if (sessionCookie != null) builder.header("Cookie", sessionCookie);
        if (sessionCsrf != null) builder.header("X-CSRF-Token", sessionCsrf);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    private HttpResponse<String> send(String method, String path, JsonNode body) throws Exception {
        return client.send(request(method, path, body, cookie, csrf), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode node(HttpResponse<String> response) { return MenuJson.createMapper().readTree(response.body()); }
    private JsonNode body(JsonNode menu, String revision) {
        var body = MenuJson.createMapper().createObjectNode().set("menu", menu);
        if (revision != null) body.put("revision", revision);
        return body;
    }
    private void login() throws Exception {
        var body = MenuJson.createMapper().createObjectNode().put("password", "acceptance-password");
        var response = client.send(request("POST", "/api/auth/login", body, null, null), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        csrf = node(response).get("csrfToken").asString();
    }

    private JsonNode create() throws Exception {
        var response = send("POST", "/api/menus", body(MenuRepositoryTest.menu("acceptance"), null));
        assertEquals(201, response.statusCode(), response.body()); return node(response);
    }

    private void consistent(JsonNode envelope) throws Exception {
        byte[] bytes = Files.readAllBytes(config.dataDirectory().resolve("menus/acceptance.json"));
        assertEquals(envelope.get("revision").asString(), MenuRepository.revision(bytes));
        assertEquals(envelope.get("menu"), MenuYaml.decode(bytes));
        assertEquals(envelope.get("menu"), MenuJson.createMapper().readTree(registry.snapshot().get("acceptance").json()));
        assertEquals(envelope, node(send("GET", "/api/menus/acceptance", null)));
    }

    @Test void invalidUpdatesLeaveExactFileAndPublishedSnapshotUntouched() throws Exception {
        JsonNode saved = create(); consistent(saved);
        byte[] original = Files.readAllBytes(config.dataDirectory().resolve("menus/acceptance.json"));
        var snapshot = registry.snapshot();
        for (String bad : List.of(
                "{\"schemaVersion\":1,\"id\":\"acceptance\",\"title\":\"坏配置\",\"dialogType\":\"confirmation\"}",
                "{\"schemaVersion\":1,\"id\":\"../escape\",\"title\":\"路径\",\"dialogType\":\"notice\"}",
                "{\"schemaVersion\":1,\"id\":\"acceptance\",\"title\":\"引用\",\"dialogType\":\"notice\",\"noticeAction\":{\"id\":\"open\",\"label\":\"打开\",\"actions\":[{\"type\":\"open_menu\",\"menuId\":\"missing\"}]}}")) {
            var response = send("PUT", "/api/menus/acceptance", body(MenuJson.createMapper().readTree(bad), saved.get("revision").asString()));
            assertEquals(422, response.statusCode(), response.body());
            assertEquals("VALIDATION_FAILED", node(response).get("code").asString());
            assertTrue(node(response).get("details").get(0).get("path").asString().startsWith("/menu/"));
            assertArrayEquals(original, Files.readAllBytes(config.dataDirectory().resolve("menus/acceptance.json")));
            assertEquals(snapshot, registry.snapshot()); consistent(saved);
        }
    }

    @Test void simultaneousHttpEditsPublishExactlyOneWinningPayload() throws Exception {
        JsonNode saved = create(); String revision = saved.get("revision").asString();
        var left = MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"acceptance\",\"title\":\"标签页 A\",\"dialogType\":\"notice\"}");
        var right = MenuJson.createMapper().readTree("{\"schemaVersion\":1,\"id\":\"acceptance\",\"title\":\"标签页 B\",\"dialogType\":\"notice\"}");
        var a = client.sendAsync(request("PUT", "/api/menus/acceptance", body(left, revision), cookie, csrf), HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(request("PUT", "/api/menus/acceptance", body(right, revision), cookie, csrf), HttpResponse.BodyHandlers.ofString());
        var responses = List.of(a.join(), b.join());
        assertEquals(List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
        var winner = responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
        var loser = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
        assertEquals("REVISION_CONFLICT", node(loser).get("code").asString());
        assertEquals(2, node(winner).get("menu").get("metadata").get("version").asInt());
        assertEquals(a.join().statusCode() == 200 ? left.get("title") : right.get("title"), node(winner).get("menu").get("title"));
        consistent(node(winner));
    }

    @Test void serviceRestartRetainsMenusButRejectsPriorSessionAndAllowsFreshLogin() throws Exception {
        JsonNode saved = create(); consistent(saved); String oldCookie = cookie, oldCsrf = csrf;
        server.stop(); startService();
        for (String endpoint : List.of("/api/auth/session", "/api/menus/acceptance", "/api/meta")) {
            var response = client.send(request("GET", endpoint, null, oldCookie, oldCsrf), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode()); assertEquals("AUTH_REQUIRED", node(response).get("code").asString());
        }
        login(); consistent(saved);
        assertEquals(204, send("DELETE", "/api/menus/acceptance", MenuJson.createMapper().createObjectNode().put("revision", saved.get("revision").asString())).statusCode());
        assertFalse(Files.exists(config.dataDirectory().resolve("menus/acceptance.json"))); assertTrue(registry.snapshot().isEmpty());
        assertEquals(204, send("POST", "/api/auth/logout", null).statusCode());
        assertEquals(401, send("GET", "/api/auth/session", null).statusCode());
    }
}
