package dialogcanvas.web.auth;

import dialogcanvas.config.PluginConfig;
import dialogcanvas.model.MenuJson;
import dialogcanvas.web.EmbeddedHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loopback HTTP tests, including Javalin before-handler behavior for unmatched routes. */
class AuthHttpTest {
    @TempDir Path directory;
    private final AuthServiceTest.MutableClock clock = new AuthServiceTest.MutableClock();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private EmbeddedHttpServer server;
    private PluginConfig config;
    private String origin;

    @BeforeEach void start() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        config = new PluginConfig(port, "127.0.0.1", AuthServiceTest.hash(), directory.resolve("data"));
        java.nio.file.Files.writeString(directory.resolve("config.yml"), "port: " + port + "\nbind-address: '127.0.0.1'\ndata-directory: data\npassword-hash: '" + config.passwordHash() + "'\n");
        origin = "http://127.0.0.1:" + port;
        server = new EmbeddedHttpServer(config, directory, new AuthService(clock, 4), ignored -> fail());
        server.startAsync().join();
    }

    @AfterEach void stop() { if (server != null) server.close(); }
    private String changeBody(String current, String next, String confirm) {
        return MenuJson.createMapper().writeValueAsString(java.util.Map.of("currentPassword", current, "newPassword", next, "confirmPassword", confirm));
    }
    @Test void passwordChangeUsesAuthOriginCsrfAndInvalidatesEverySession() throws Exception {
        String body = changeBody(AuthServiceTest.PASSWORD, "new-password", "new-password");
        error(request("POST", "/api/auth/password", body, null, null, origin), 401, "AUTH_REQUIRED");
        var one = login(); var two = login();
        error(request("POST", "/api/auth/password", body, cookie(one), csrf(one), "http://evil.example"), 403, "ORIGIN_INVALID");
        error(request("POST", "/api/auth/password", body, cookie(one), null, origin), 403, "CSRF_INVALID");
        var changed = request("POST", "/api/auth/password", body, cookie(one), csrf(one), origin);
        assertEquals(204, changed.statusCode()); assertEquals("", changed.body()); assertTrue(changed.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        error(request("GET", "/api/auth/session", null, cookie(one), null, null), 401, "AUTH_REQUIRED");
        error(request("GET", "/api/auth/session", null, cookie(two), null, null), 401, "AUTH_REQUIRED");
        error(login(), 401, "INVALID_CREDENTIALS");
        assertEquals(200, request("POST", "/api/auth/login", "{\"password\":\"new-password\"}", null, null, origin).statusCode());
        server.close(); config = PluginConfig.load(directory);
        server = new EmbeddedHttpServer(config, directory, new AuthService(clock, 4), ignored -> fail()); server.startAsync().join();
        assertEquals(200, request("POST", "/api/auth/login", "{\"password\":\"new-password\"}", null, null, origin).statusCode());
    }
    @Test void incorrectCurrentPasswordDoesNotLogOutAndIsRateLimited() throws Exception {
        var login = login();
        String body = changeBody("wrong", "new-password", "new-password");
        for (int i = 0; i < 5; i++) error(request("POST", "/api/auth/password", body, cookie(login), csrf(login), origin), 403, "CURRENT_PASSWORD_INCORRECT");
        error(request("POST", "/api/auth/password", body, cookie(login), csrf(login), origin), 429, "LOGIN_RATE_LIMITED");
        assertEquals(200, request("GET", "/api/auth/session", null, cookie(login), null, null).statusCode());
    }
    @Test void passwordRequestFieldsAndValidationAreStrict() throws Exception {
        var login = login();
        for (String body : java.util.List.of("{}", "{invalid", "{\"currentPassword\":null,\"newPassword\":\"12345678\",\"confirmPassword\":\"12345678\"}"))
            error(request("POST", "/api/auth/password", body, cookie(login), csrf(login), origin), 400, "INVALID_REQUEST");
        error(request("POST", "/api/auth/password", changeBody(AuthServiceTest.PASSWORD, "short", "short"), cookie(login), csrf(login), origin), 422, "INVALID_NEW_PASSWORD");
        error(request("POST", "/api/auth/password", changeBody(AuthServiceTest.PASSWORD, "new-password", "other-password"), cookie(login), csrf(login), origin), 422, "PASSWORD_MISMATCH");
        assertEquals(200, request("GET", "/api/auth/session", null, cookie(login), null, null).statusCode());
    }

    private HttpResponse<String> request(String method, String path, String body, String cookie,
                                         String csrf, String requestOrigin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        if (requestOrigin != null) request.header("Origin", requestOrigin);
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login() throws Exception {
        return request("POST", "/api/auth/login", "{\"password\":\"" + AuthServiceTest.PASSWORD + "\"}", null, null, origin);
    }
    private String cookie(HttpResponse<String> response) { return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0]; }
    private String csrf(HttpResponse<String> response) { return MenuJson.createMapper().readTree(response.body()).get("csrfToken").asString(); }
    private void error(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        var body = MenuJson.createMapper().readTree(response.body());
        assertEquals(2, body.size()); assertEquals(code, body.get("code").asString()); assertTrue(body.get("error").isString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void loginUsesHttpCompatibleCookieAndSessionDoesNotExposeToken() throws Exception {
        var login = login(); assertEquals(200, login.statusCode());
        String header = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Path=/api")); assertTrue(header.contains("Max-Age=86400"));
        assertFalse(header.contains("Secure")); assertFalse(header.contains("Domain="));
        assertFalse(login.body().contains(cookie(login).substring(cookie(login).indexOf('=') + 1)));
        var session = request("GET", "/api/auth/session", null, cookie(login), null, null);
        assertEquals(200, session.statusCode()); assertEquals(csrf(login), csrf(session));
        assertEquals("2026-10-12T00:00:00Z", MenuJson.createMapper().readTree(session.body()).get("expiresAt").asString());
    }

    @Test void cookieManagerAutomaticallySendsCookieOverPlainLoopbackHttp() throws Exception {
        var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        var browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        var loginRequest = HttpRequest.newBuilder(URI.create(origin + "/api/auth/login"))
                .header("Origin", origin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + AuthServiceTest.PASSWORD + "\"}")).build();
        assertEquals(200, browser.send(loginRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        var sessionRequest = HttpRequest.newBuilder(URI.create(origin + "/api/auth/session")).GET().build();
        assertEquals(200, browser.send(sessionRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, cookies.getCookieStore().getCookies().size());
        assertTrue(cookies.getCookieStore().getCookies().getFirst().isHttpOnly());
    }

    @Test void allApiPathsAndMethodsRequireAuthenticationExceptExactPostLogin() throws Exception {
        for (String path : java.util.List.of("/api/auth/session", "/api/menus", "/api/meta", "/api/unknown", "/api", "/%61pi/auth/session",
                "/api/menus/hello%20world", "/api/menus/%5Bbroken")) {
            error(request("GET", path, null, null, null, null), 401, "AUTH_REQUIRED");
        }
        error(request("GET", "/api/auth/login", null, null, null, null), 401, "AUTH_REQUIRED");
        error(request("OPTIONS", "/api/menus", null, null, null, origin), 401, "AUTH_REQUIRED");
        error(request("POST", "/api/auth/logout", null, null, null, origin), 401, "AUTH_REQUIRED");
        assertEquals("ok", request("GET", "/health", null, null, null, null).body());
        var login = login();
        assertEquals(200, request("GET", "/api/menus", null, cookie(login), null, null).statusCode());
        error(request("GET", "/api/unknown", null, cookie(login), null, null), 404, "API_NOT_FOUND");
    }

    @Test void encodedCanonicalPathsCannotBypassAuthenticationOrBecomeLoginExemptions() throws Exception {
        for (String path : java.util.List.of("//api/auth/session", "/x/../api/meta", "/api//meta", "/%61pi/auth/login", "/api/auth/login/", "/api/auth/login%3Fignored")) {
            var response = request("POST", path, "{\"password\":\"" + AuthServiceTest.PASSWORD + "\"}", null, null, origin);
            assertTrue(response.statusCode() == 401 || response.statusCode() == 400, "Canonical path accepted without authentication: " + path + " (" + response.statusCode() + ")");
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        }
        for (String method : java.util.List.of("PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
            assertEquals(401, request(method, "/api/auth/login", null, null, null, origin).statusCode());
    }

    @Test void everyRegisteredApiEndpointRequiresAuthAndCredentialParseErrorsAreSanitized() throws Exception {
        for (String route : java.util.List.of("GET /api/menus", "GET /api/menus/example", "POST /api/menus", "PUT /api/menus/example",
                "DELETE /api/menus/example", "POST /api/menus/validate", "POST /api/menus/import-yaml", "POST /api/menus/export-yaml", "GET /api/meta", "POST /api/auth/password", "POST /api/auth/logout", "GET /api/auth/session")) {
            String[] parts = route.split(" ", 2);
            error(request(parts[0], parts[1], parts[0].equals("GET") ? null : "{}", null, null, origin), 401, "AUTH_REQUIRED");
        }
        String marker = "fixture-sensitive-marker";
        var response = request("POST", "/api/auth/login", "{\"password\":\"" + marker + "\"", null, null, origin);
        error(response, 400, "INVALID_REQUEST"); assertFalse(response.body().contains(marker));
        var login = login();
        var password = request("POST", "/api/auth/password", "{\"currentPassword\":\"" + marker + "\"", cookie(login), csrf(login), origin);
        error(password, 400, "INVALID_REQUEST"); assertFalse(password.body().contains(marker));
    }

    @Test void wrongPasswordsAreUniformAndFailuresAreLimited() throws Exception {
        for (int i = 0; i < 5; i++) error(request("POST", "/api/auth/login", "{\"password\":\"wrong\"}", null, null, origin), 401, "INVALID_CREDENTIALS");
        var limited = login(); error(limited, 429, "LOGIN_RATE_LIMITED");
        assertEquals("60", limited.headers().firstValue("Retry-After").orElseThrow());
        clock.advance(Duration.ofMinutes(1)); assertEquals(200, login().statusCode());
    }

    @Test void writesRejectForeignOrMissingOriginAndInvalidCsrfWithoutLoggingOut() throws Exception {
        error(request("POST", "/api/auth/login", "{\"password\":\"x\"}", null, null, "http://evil.example"), 403, "ORIGIN_INVALID");
        error(request("POST", "/api/auth/login", "{\"password\":\"x\"}", null, null, null), 403, "ORIGIN_INVALID");
        var login = login();
        error(request("POST", "/api/auth/logout", null, cookie(login), csrf(login), "http://evil.example"), 403, "ORIGIN_INVALID");
        error(request("POST", "/api/auth/logout", null, cookie(login), "wrong", origin), 403, "CSRF_INVALID");
        assertEquals(200, request("GET", "/api/auth/session", null, cookie(login), null, null).statusCode());
        assertTrue(login.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test void logoutImmediatelyInvalidatesCookie() throws Exception {
        var login = login();
        var logout = request("POST", "/api/auth/logout", null, cookie(login), csrf(login), origin);
        assertEquals(204, logout.statusCode()); assertEquals("", logout.body());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        error(request("GET", "/api/auth/session", null, cookie(login), null, null), 401, "AUTH_REQUIRED");
    }

    @Test void expiryAndRestartInvalidatePreviouslyValidSessions() throws Exception {
        var first = login(); clock.advance(Duration.ofHours(24));
        error(request("GET", "/api/auth/session", null, cookie(first), null, null), 401, "AUTH_REQUIRED");
        var second = login(); server.close();
        server = new EmbeddedHttpServer(config, directory, new AuthService(clock, 4), ignored -> fail());
        server.startAsync().join();
        error(request("GET", "/api/auth/session", null, cookie(second), null, null), 401, "AUTH_REQUIRED");
        assertEquals(200, login().statusCode());
    }

    @Test void malformedOrDuplicateLoginFieldsReturnJsonErrors() throws Exception {
        for (String body : java.util.List.of("{invalid", "{\"password\":\"x\",\"password\":\"y\"}", "{}", "{\"password\":null}", "{\"password\":\"x\",\"extra\":true}", "{\"password\":\"x\"}{}")) {
            error(request("POST", "/api/auth/login", body, null, null, origin), 400, "INVALID_REQUEST");
        }
    }

    @Test void oversizedLoginBodyAndWrongContentTypeReturnJsonErrors() throws Exception {
        error(request("POST", "/api/auth/login", "{\"password\":\"" + "x".repeat(1024 * 1024) + "\"}", null, null, origin),
                413, "HTTP_413");
        var wrongType = HttpRequest.newBuilder(URI.create(origin + "/api/auth/login"))
                .header("Origin", origin).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("password=x")).build();
        error(client.send(wrongType, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
    }
}
