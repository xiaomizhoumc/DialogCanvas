package dialogcanvas.web.menu;

import at.favre.lib.crypto.bcrypt.BCrypt;
import dialogcanvas.config.PluginConfig;
import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.web.EmbeddedHttpServer;
import dialogcanvas.web.auth.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MenuHttpTest {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();
    private EmbeddedHttpServer server;
    private String origin, cookie, csrf;
    private MenuRepositoryTest.FaultStore files;
    private MenuRepositoryTest.FaultRegistry registry;
    private dialogcanvas.audit.AuditLogger audit;
    private String configuredHash;
    @BeforeEach void start() throws Exception {
        int port; try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        origin = "http://127.0.0.1:" + port;
        var config = new PluginConfig(port, "127.0.0.1", BCrypt.withDefaults().hashToString(4, "test-password".toCharArray()), directory);
        configuredHash = config.passwordHash();
        Files.writeString(directory.resolve("config.yml"), "password-hash: '" + configuredHash + "'\n");
        files = new MenuRepositoryTest.FaultStore(directory); registry = new MenuRepositoryTest.FaultRegistry();
        var menus = new MenuRepository(files, MenuRepositoryTest.validator(), registry, Clock.systemUTC());
        server = new EmbeddedHttpServer(config, directory, new AuthService(Clock.systemUTC(), 4), ignored -> fail(), menus);
        audit = new dialogcanvas.audit.AuditLogger(directory, java.util.logging.Logger.getAnonymousLogger()); menus.auditLogger(audit); server.auditLogger(audit);
        server.startAsync().join();
        var login = send("POST", "/api/auth/login", "{\"password\":\"test-password\"}", false);
        assertEquals(200, login.statusCode());
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0]; csrf = node(login).get("csrfToken").asString();
    }
    @AfterEach void close() { if (server != null) server.close(); if (audit != null) audit.closeAsync().join(); }
    @Test void auditIncludesActualWebResultsAndMalformedWritesButNoCredentialsOrReadNoise() throws Exception {
        send("GET", "/api/auth/session", null, true); send("GET", "/api/meta", null, true); send("GET", "/api/menus", null, true);
        assertEquals(400, send("POST", "/api/menus", "{bad-private-body", true).statusCode());
        var created = create("audit_shop"); assertEquals(201, created.statusCode());
        String failedPassword = "{\"currentPassword\":\"wrong-private-password\",\"newPassword\":\"new-test-password\",\"confirmPassword\":\"new-test-password\"}";
        assertEquals(403, send("POST", "/api/auth/password", failedPassword, true).statusCode());
        assertEquals(204, send("POST", "/api/auth/password", failedPassword.replace("wrong-private-password", "test-password"), true).statusCode());
        server.close(); audit.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
        var records = Files.readAllLines(directory.resolve("logs/audit.log")).stream().map(MenuJson.createMapper()::readTree).toList();
        assertEquals(4, records.size());
        assertEquals("REJECTED", records.getFirst().path("result").asString()); assertEquals("INVALID_JSON", records.getFirst().path("code").asString());
        assertEquals("SUCCESS", records.get(1).path("result").asString());
        assertEquals("CURRENT_PASSWORD_INCORRECT", records.get(2).path("code").asString()); assertEquals("PASSWORD_CHANGED", records.get(3).path("code").asString());
        assertTrue(records.stream().allMatch(row -> row.path("actor").asString().equals("local-admin-session")));
        String raw = Files.readString(directory.resolve("logs/audit.log"));
        for (String secret : java.util.List.of(cookie.split("=", 2)[1], csrf, configuredHash, "test-password", "wrong-private-password", "new-test-password", "bad-private-body")) assertFalse(raw.contains(secret));
    }
    private HttpResponse<String> send(String method, String path, String body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).header("Origin", origin);
        if (authenticated) request.header("Cookie", cookie).header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode node(HttpResponse<String> response) { return MenuJson.createMapper().readTree(response.body()); }
    private HttpResponse<String> create(String id) throws Exception { return send("POST", "/api/menus", "{\"menu\":" + MenuRepositoryTest.menu(id) + "}", true); }
    private HttpResponse<String> upload(byte[] yaml) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(origin + "/api/menus/import-yaml"))
                .header("Origin", origin).header("Cookie", cookie).header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/yaml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(yaml)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> upload(String yaml) throws Exception { return upload(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    @Test void browserYamlRoundTripPreservesRawDocumentAndDoesNotPersist() throws Exception {
        var menu = MenuRepositoryTest.menu("local_content_id").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) menu).putNull("externalTitle");
        String yaml = "# hand-written comment\n" + new String(dialogcanvas.storage.MenuYaml.encode(menu), java.nio.charset.StandardCharsets.UTF_8);
        var preview = upload(yaml); assertEquals(200, preview.statusCode(), preview.body());
        assertEquals(menu, node(preview).get("menu")); assertTrue(node(preview).get("existing").isNull());
        assertTrue(node(preview).get("valid").asBoolean()); assertTrue(node(preview).get("warnings").isArray());
        var exported = send("POST", "/api/menus/export-yaml", "{\"menu\":" + menu + "}", true);
        assertEquals(200, exported.statusCode()); assertEquals("draft", node(exported).get("source").asString());
        assertEquals("local_content_id-draft.yml", node(exported).get("fileName").asString());
        String generated = node(exported).get("yaml").asString(); assertFalse(generated.contains("hand-written comment"));
        assertEquals(menu, node(upload(generated)).get("menu"));
        assertTrue(registry.snapshot().isEmpty()); assertEquals(0, node(send("GET", "/api/menus", null, true)).get("items").size());
        assertFalse(Files.exists(directory.resolve("menus/local_content_id.json")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"id: [unclosed", "id: a\nid: b\n", "!!java/object:java.lang.Runtime {}", "- not-a-root-object", "base: &base {title: x}\ncopy: *base"})
    void browserRejectsUnsafeOrInvalidYamlWithoutLeakingSource(String yaml) throws Exception {
        var response = upload(yaml); error(response, 422, "VALIDATION_FAILED");
        assertEquals("INVALID_YAML", node(response).get("details").get(0).get("code").asString());
        assertTrue(registry.snapshot().isEmpty()); assertFalse(response.body().contains(yaml));
    }
    @Test void browserRejectsUnknownFieldsForeignFormatsAndInvalidUtf8() throws Exception {
        var foreign = upload("menu_title: KaMenu\nsize: 9\n"); error(foreign, 422, "VALIDATION_FAILED");
        assertEquals("UNSUPPORTED_MENU_FORMAT", node(foreign).get("details").get(0).get("code").asString());
        var menu = (tools.jackson.databind.node.ObjectNode) MenuRepositoryTest.menu("unknown_field"); menu.put("serverPath", "test-private-path");
        var unknown = upload(new String(dialogcanvas.storage.MenuYaml.encode(menu), java.nio.charset.StandardCharsets.UTF_8));
        error(unknown, 422, "VALIDATION_FAILED"); assertFalse(unknown.body().contains("test-private-path"));
        assertEquals("INVALID_FIELD", node(unknown).get("details").get(0).get("code").asString());
        var invalid = upload(new byte[]{(byte) 0xc3, (byte) 0x28}); error(invalid, 422, "VALIDATION_FAILED");
        assertEquals("INVALID_YAML", node(invalid).get("details").get(0).get("code").asString());
        assertTrue(registry.snapshot().isEmpty());
    }
    @Test void browserUploadSizeAndSourceGuardsApplyToBothConversionEndpoints() throws Exception {
        error(upload("#" + "x".repeat(1024 * 1024)), 413, "HTTP_413");
        error(send("POST", "/api/menus/import-yaml", "{}", true), 415, "UNSUPPORTED_MEDIA_TYPE");
        for (String endpoint : java.util.List.of("import-yaml", "export-yaml")) {
            error(send("POST", "/api/menus/" + endpoint, "{}", false), 401, "AUTH_REQUIRED");
            var badCsrf = HttpRequest.newBuilder(URI.create(origin + "/api/menus/" + endpoint))
                    .header("Origin", origin).header("Cookie", cookie).header("X-CSRF-Token", "wrong")
                    .header("Content-Type", "application/yaml").POST(HttpRequest.BodyPublishers.ofString("test"));
            error(client.send(badCsrf.build(), HttpResponse.BodyHandlers.ofString()), 403, "CSRF_INVALID");
            var badOrigin = HttpRequest.newBuilder(URI.create(origin + "/api/menus/" + endpoint))
                    .header("Origin", "http://foreign.example").header("Cookie", cookie).header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("test"));
            error(client.send(badOrigin.build(), HttpResponse.BodyHandlers.ofString()), 403, "ORIGIN_INVALID");
        }
        assertTrue(registry.snapshot().isEmpty());
    }
    @Test void sameNameImportCapturesRevisionAndStillUsesNormalUpdateConflictChecks() throws Exception {
        var created = node(create("same_name"));
        byte[] before = Files.readAllBytes(directory.resolve("menus/same_name.json"));
        String yaml = new String(dialogcanvas.storage.MenuYaml.encode(created.get("menu")), java.nio.charset.StandardCharsets.UTF_8);
        var preview = node(upload(yaml)); assertEquals(created.get("revision"), preview.get("existing").get("revision"));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("menus/same_name.json")));
        var updated = send("PUT", "/api/menus/same_name", "{\"revision\":" + created.get("revision") + ",\"menu\":" + created.get("menu") + "}", true);
        assertEquals(200, updated.statusCode());
        error(send("PUT", "/api/menus/same_name", "{\"revision\":" + preview.get("existing").get("revision") + ",\"menu\":" + preview.get("menu") + "}", true), 409, "REVISION_CONFLICT");
        error(send("POST", "/api/menus", "{\"menu\":" + MenuRepositoryTest.menu("same_name") + "}", true), 409, "MENU_ALREADY_EXISTS");
    }
    @Test void importChecksReferencesAndNeverBypassesPendingManualYaml() throws Exception {
        var created = node(create("manual_pending"));
        Path manual = directory.resolve("manual-menus/manual_pending.yml");
        Files.writeString(manual, Files.readString(manual).replace("&6欢迎", "手动未导入"));
        byte[] changed = Files.readAllBytes(manual);
        var preview = upload(new String(dialogcanvas.storage.MenuYaml.encode(created.get("menu")), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, preview.statusCode()); assertEquals("pendingImport", node(preview).get("existing").get("syncStatus").asString());
        error(send("PUT", "/api/menus/manual_pending", "{\"revision\":" + created.get("revision") + ",\"menu\":" + created.get("menu") + "}", true), 409, "YAML_SYNC_CONFLICT");
        assertArrayEquals(changed, Files.readAllBytes(manual));
        String missing = "schemaVersion: 1\nid: bad_reference\ntitle: test\ndialogType: notice\nnoticeAction:\n  id: open\n  label: open\n  actionType: custom\n  actions:\n    - type: open_menu\n      menuId: nonexistent\n";
        var rejected = upload(missing); error(rejected, 422, "VALIDATION_FAILED");
        assertEquals("MENU_REFERENCE_NOT_FOUND", node(rejected).get("details").get(0).get("code").asString());
        assertTrue(node(rejected).get("details").get(0).get("path").asString().contains("menuId"));
        error(send("POST", "/api/menus/export-yaml", "{\"menu\":{}}", true), 422, "VALIDATION_FAILED");
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, node(response).get("code").asString());
        assertTrue(node(response).get("error").isString()); assertFalse(response.body().contains("Exception"));
        assertFalse(response.body().contains(directory.toString()));
    }
    @Test void authenticatedCrudPersistsAndPublishesBeforeSuccess() throws Exception {
        var created = create("welcome"); assertEquals(201, created.statusCode());
        assertEquals("/api/menus/welcome", created.headers().firstValue("Location").orElseThrow());
        assertTrue(Files.exists(directory.resolve("menus/welcome.json"))); assertTrue(registry.snapshot().containsKey("welcome"));
        var fetched = send("GET", "/api/menus/welcome", null, true); assertEquals(node(created), node(fetched));
        assertEquals(1, node(send("GET", "/api/menus", null, true)).get("items").size());
        String revision = node(created).get("revision").asString();
        var updated = send("PUT", "/api/menus/welcome", "{\"revision\":\"" + revision + "\",\"menu\":" + node(created).get("menu") + "}", true);
        assertEquals(200, updated.statusCode(), updated.body()); assertNotEquals(revision, node(updated).get("revision").asString());
        assertEquals(2, node(updated).get("menu").get("metadata").get("version").asInt());
        String delete = "{\"revision\":\"" + node(updated).get("revision").asString() + "\"}";
        assertEquals(204, send("DELETE", "/api/menus/welcome", delete, true).statusCode());
        assertFalse(Files.exists(directory.resolve("menus/welcome.json"))); assertFalse(registry.snapshot().containsKey("welcome"));
        error(send("GET", "/api/menus/welcome", null, true), 404, "MENU_NOT_FOUND");
    }
    @Test void legacyPauseIsPreservedButIgnoredOnDedicatedServer() throws Exception {
        var created = create("welcome"); assertEquals(201, created.statusCode());
        var original = node(created);
        var menu = (tools.jackson.databind.node.ObjectNode) original.get("menu").deepCopy();
        menu.put("pause", true); menu.put("afterAction", "NONE");
        String body = "{\"revision\":\"" + original.get("revision").asString() + "\",\"menu\":" + menu + "}";
        var updated = send("PUT", "/api/menus/welcome", body, true);
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(node(updated).get("menu").get("pause").asBoolean());
        assertTrue(updated.body().contains("SERVER_PAUSE_IGNORED"));
        var validated = send("POST", "/api/menus/validate", "{\"menu\":" + menu + "}", true);
        assertEquals(200, validated.statusCode(), validated.body());
        assertTrue(validated.body().contains("SERVER_PAUSE_IGNORED"));
        assertFalse(dialogcanvas.dialog.DialogBehavior.effectivePause(
                MenuJson.createMapper().treeToValue(menu, dialogcanvas.model.MenuDefinition.class)));
        assertTrue(MenuJson.createMapper().readTree(Files.readString(directory.resolve("menus/welcome.json"))).get("pause").asBoolean());
        assertTrue(registry.snapshot().containsKey("welcome"));
        assertEquals(node(updated), node(send("GET", "/api/menus/welcome", null, true)));
    }
    @Test void authenticationAndCsrfStillProtectAllMenuRoutes() throws Exception {
        error(send("GET", "/api/menus", null, false), 401, "AUTH_REQUIRED");
        error(send("GET", "/api/meta", null, false), 401, "AUTH_REQUIRED");
        error(send("POST", "/api/menus", "{\"menu\":" + MenuRepositoryTest.menu("welcome") + "}", false), 401, "AUTH_REQUIRED");
        var withoutCsrf = HttpRequest.newBuilder(URI.create(origin + "/api/menus")).header("Cookie", cookie)
                .header("Origin", origin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"menu\":" + MenuRepositoryTest.menu("welcome") + "}")).build();
        error(client.send(withoutCsrf, HttpResponse.BodyHandlers.ofString()), 403, "CSRF_INVALID");
        assertTrue(registry.snapshot().isEmpty());
    }
    @Test void conflictsAndMissingRevisionNeverSilentlyOverwrite() throws Exception {
        var created = create("welcome"); String revision = node(created).get("revision").asString();
        error(create("welcome"), 409, "MENU_ALREADY_EXISTS");
        error(send("PUT", "/api/menus/welcome", "{\"menu\":" + MenuRepositoryTest.menu("welcome") + "}", true), 428, "REVISION_REQUIRED");
        var request = "{\"revision\":\"" + revision + "\",\"menu\":" + MenuRepositoryTest.menu("welcome") + "}";
        assertEquals(200, send("PUT", "/api/menus/welcome", request, true).statusCode());
        error(send("PUT", "/api/menus/welcome", request, true), 409, "REVISION_CONFLICT");
        error(send("DELETE", "/api/menus/welcome", "{\"revision\":\"" + revision + "\"}", true), 409, "REVISION_CONFLICT");
    }
    @Test void validationDoesNotPersistAndReturnsFieldPaths() throws Exception {
        assertEquals(200, send("POST", "/api/menus/validate", "{\"menu\":" + MenuRepositoryTest.menu("draft") + "}", true).statusCode());
        assertTrue(registry.snapshot().isEmpty()); assertFalse(Files.exists(directory.resolve("menus/draft.json")));
        var invalid = send("POST", "/api/menus/validate", "{\"menu\":{\"schemaVersion\":1,\"id\":\"draft\",\"title\":\"Draft\",\"dialogType\":\"confirmation\"}}", true);
        error(invalid, 422, "VALIDATION_FAILED"); assertEquals("/menu/yesButton", node(invalid).get("details").get(0).get("path").asString());
        error(send("POST", "/api/menus", "{\"menu\":{" + "\"id\":\"a\",\"id\":\"b\"}}", true), 400, "INVALID_JSON");
        error(send("GET", "/api/menus/CON", null, true), 400, "INVALID_MENU_ID");
        String deep = "[".repeat(70) + "0" + "]".repeat(70);
        error(send("POST", "/api/menus/validate", "{\"menu\":{\"nested\":" + deep + "}}", true), 400, "INVALID_JSON");
    }
    @Test void missingReferencesAndReferencedDeleteReturnSpecificErrors() throws Exception {
        var target = create("target");
        String referencing = "{\"schemaVersion\":1,\"id\":\"source\",\"title\":\"Source\",\"dialogType\":\"notice\",\"noticeAction\":{\"id\":\"open\",\"label\":\"Open\",\"actions\":[{\"type\":\"open_menu\",\"menuId\":\"target\"}]}}";
        assertEquals(201, send("POST", "/api/menus", "{\"menu\":" + referencing + "}", true).statusCode());
        var deleted = send("DELETE", "/api/menus/target", "{\"revision\":\"" + node(target).get("revision").asString() + "\"}", true);
        error(deleted, 409, "MENU_REFERENCED"); assertTrue(node(deleted).get("details").get(0).get("path").asString().startsWith("/menus/source/"));
        var invalid = send("POST", "/api/menus/validate", "{\"menu\":" + referencing.replace("target", "missing") + "}", true);
        error(invalid, 422, "VALIDATION_FAILED"); assertEquals("MENU_REFERENCE_NOT_FOUND", node(invalid).get("details").get(0).get("code").asString());
    }
    @Test void storageAndPublicationFailuresNeverReturnSuccessOrStackTraces() throws Exception {
        var created = create("welcome"); var before = registry.snapshot(); String revision = node(created).get("revision").asString();
        String body = "{\"revision\":\"" + revision + "\",\"menu\":" + MenuRepositoryTest.menu("welcome") + "}";
        files.failWriteAt = 1; error(send("PUT", "/api/menus/welcome", body, true), 500, "STORAGE_WRITE_FAILED");
        assertEquals(before, registry.snapshot());
        registry.failNext = true; error(send("PUT", "/api/menus/welcome", body, true), 500, "REGISTRY_PUBLISH_FAILED");
        assertEquals(before, registry.snapshot());
    }
    @Test void metadataAdvertisesOnlyImplementedCapabilitiesAndExistingPojoSchema() throws Exception {
        var response = send("GET", "/api/meta", null, true); assertEquals(200, response.statusCode(), response.body());
        var meta = node(response); assertEquals(1, meta.get("apiVersion").asInt());
        assertEquals(3, meta.get("capabilities").get("dialogTypes").size());
        assertFalse(meta.get("capabilities").get("actionEngine").get("productionConnected").asBoolean());
        assertTrue(meta.get("capabilities").get("frontendEditor").asBoolean());
        assertTrue(meta.get("capabilities").get("gameMenuOpeningConnected").asBoolean());
        for (String unsupported : Set.of("templates", "history", "diff", "webSocket", "gamePreview", "serverLimit")) {
            assertFalse(meta.get("capabilities").get(unsupported).asBoolean());
        }
        assertTrue(meta.get("schemas").get("MenuDefinition").get("$defs").has("ActionDefinition"));
        assertFalse(meta.get("schemas").get("MenuDefinition").get("$defs").get("MenuDefinition").get("properties").has("dialogs"));
        assertFalse(meta.get("schemas").get("MenuDefinition").get("$defs").get("ActionDefinition").get("properties").has("clickEvent"));
        assertTrue(meta.get("iconMappings").size() > 1300);
        assertFalse(meta.get("iconMappings").get("DIAMOND").has("thumbnail"));
        assertFalse(response.body().contains("passwordHash"));
    }
    @Test void registryIconMetadataDoesNotAdvertiseOrServeBundledTextures() throws Exception {
        var meta = node(send("GET", "/api/meta", null, true));
        for (String material : java.util.List.of("DIAMOND", "GRASS_BLOCK", "POTION")) {
            var mapping = meta.get("iconMappings").get(material);
            assertFalse(mapping.has("thumbnail"));
            assertTrue(mapping.get("sprite").asString().startsWith("minecraft:"));
        }
        assertNull(getClass().getResourceAsStream("/web/vanilla/26.1.2/item/diamond.png"));
        var response = client.send(HttpRequest.newBuilder(URI.create(origin + "/vanilla/26.1.2/item/diamond.png")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(404, response.statusCode());
    }
}
