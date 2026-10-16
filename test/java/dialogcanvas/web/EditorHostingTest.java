package dialogcanvas.web;

import at.favre.lib.crypto.bcrypt.BCrypt;
import dialogcanvas.config.PluginConfig;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.web.auth.AuthService;
import dialogcanvas.web.menu.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class EditorHostingTest {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ServerSocket reserve(int port) throws Exception {
        return new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"));
    }

    private EmbeddedHttpServer create(int port, AuthService auth, String resourceDirectory) {
        String hash = BCrypt.withDefaults().hashToString(4, "test-password".toCharArray());
        var config = new PluginConfig(port, "127.0.0.1", hash, directory.resolve("data"));
        var menus = new MenuRepository(new FileStorage(config.dataDirectory()), new MenuValidator(new MenuEnvironment(Set.of(), Set.of())));
        return new EmbeddedHttpServer(config, directory, auth, ignored -> fail("Must not print an existing password"), menus, resourceDirectory);
    }

    private HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void asynchronousStopInvalidatesAddressWithoutWaitingForLifecycleLockAndReleasesPort() throws Exception {
        int port;
        try (var socket = reserve(0)) { port = socket.getLocalPort(); }
        try (var server = create(port, new AuthService(Clock.systemUTC(), 4), "/editor-fixture")) {
            server.startAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var holding = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var owner = java.util.concurrent.CompletableFuture.runAsync(() -> {
                synchronized (server) {
                    holding.countDown();
                    try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new RuntimeException(failure); }
                }
            });
            try {
                assertTrue(holding.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var stopped = server.stopAsync();
                assertFalse(stopped.isDone(), "Listener shutdown must run independently of the calling thread");
                assertEquals(EmbeddedHttpServer.State.STOPPED, server.status().state());
                assertNull(server.status().address());
                release.countDown();
                stopped.get(5, java.util.concurrent.TimeUnit.SECONDS);
                owner.get(5, java.util.concurrent.TimeUnit.SECONDS);
                try (var available = reserve(port)) { assertEquals(port, available.getLocalPort()); }
            } finally { release.countDown(); }
        }
    }

    @Test void consoleShowsActualConfiguredAddressAndRootExplainsMissingEditor() throws Exception {
        int port;
        try (var socket = reserve(0)) { port = socket.getLocalPort(); }
        try (var server = create(port, new AuthService(Clock.systemUTC(), 4), "/missing-editor-fixture")) {
            server.startAsync().join();
            assertEquals("http://127.0.0.1:" + port + "/", server.status().address());
            var mock = MockBukkit.mock();
            try {
                var console = mock.getConsoleSender();
                EditorEntry.print(server.status(), console::sendMessage);
                assertEquals("[DCMenu] 本机编辑器地址：http://127.0.0.1:" + port + "/", console.nextMessage());
                assertTrue(console.nextMessage().contains("服务器所在电脑"));
            } finally { MockBukkit.unmock(); }
            var root = get(server.status().address());
            assertEquals(200, root.statusCode());
            assertTrue(root.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
            assertTrue(root.body().contains("编辑器尚未安装完成"));
            assertFalse(root.body().contains("test-password"));
            assertEquals(401, get(server.status().address() + "api/menus").statusCode());
            assertEquals(404, get(server.status().address() + "assets/missing.js").statusCode());
        }
    }

    @Test void jarClasspathBundleAndAssetsAreServedOnSameOriginWithoutExposingOtherResources() throws Exception {
        int port;
        try (var socket = reserve(0)) { port = socket.getLocalPort(); }
        try (var server = create(port, new AuthService(Clock.systemUTC(), 4), "/editor-fixture")) {
            server.startAsync().join();
            String origin = server.status().address();
            assertEquals(200, get(origin).statusCode());
            assertTrue(get(origin).body().contains("fixture frontend"));
            var revalidated = client.send(HttpRequest.newBuilder(URI.create(origin))
                    .header("If-None-Match", "W/\"old-same-length-index\"").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, revalidated.statusCode());
            assertEquals("no-store", revalidated.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(revalidated.headers().firstValue("ETag").isEmpty());
            var asset = get(origin + "assets/app.js");
            assertEquals(200, asset.statusCode());
            assertTrue(asset.body().contains("fixtureAsset"));
            assertTrue(asset.headers().firstValue("Content-Type").orElseThrow().contains("javascript"));
            assertEquals(401, get(origin + "api/auth/session").statusCode());
            assertEquals(404, get(origin + "paper-plugin.yml").statusCode());
            assertNotEquals(200, get(origin + "assets/../../paper-plugin.yml").statusCode());
        }
    }

    @Test void occupiedPortReportsFailureWithoutAddressAndReleasesWebResources() throws Exception {
        var auth = new AuthService(Clock.systemUTC(), 4);
        try (var occupied = reserve(0);
             var server = create(occupied.getLocalPort(), auth, "/web")) {
            assertThrows(CompletionException.class, () -> server.startAsync().join());
            assertEquals(EmbeddedHttpServer.State.FAILED, server.status().state());
            assertNull(server.status().address());
            assertTrue(server.status().diagnostic().contains("端口 " + occupied.getLocalPort()));
            assertTrue(server.status().diagnostic().contains("占用"));
            var lines = new java.util.ArrayList<String>();
            EditorEntry.print(server.status(), lines::add);
            assertTrue(lines.stream().noneMatch(line -> line.contains("http://")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("取决于菜单服务状态")));
            assertFalse(server.menus().ready());
            assertThrows(CompletionException.class, () -> auth.login("test-password").join());
        }
    }

    @Test void bootstrapPersistenceFailureNeverAdvertisesAnAuthenticatedEditorOrBindsPort() throws Exception {
        int port;
        try (var reserved = reserve(0)) { port = reserved.getLocalPort(); }
        java.nio.file.Files.createDirectory(directory.resolve("config.yml"));
        var config = new PluginConfig(port, "127.0.0.1", "", directory.resolve("data"));
        var auth = new AuthService(Clock.systemUTC(), 4);
        try (var server = new EmbeddedHttpServer(config, directory, auth, ignored -> fail("Failed persistence must not print"))) {
            assertThrows(CompletionException.class, () -> server.startAsync().join());
            assertEquals(EmbeddedHttpServer.State.FAILED, server.status().state());
            assertNull(server.status().address()); assertFalse(server.menus().ready());
            assertThrows(CompletionException.class, () -> auth.login("fixture-only").join());
            try (var available = reserve(port)) { assertEquals(port, available.getLocalPort()); }
        }
    }

    @Test void stopInvalidatesSessionsClosesRepositoryAndFreesPort() throws Exception {
        int port;
        try (var socket = reserve(0)) { port = socket.getLocalPort(); }
        var auth = new AuthService(Clock.systemUTC(), 4);
        try (var server = create(port, auth, "/web")) {
            server.startAsync().join();
            String token = auth.login("test-password").join().token();
            assertNotNull(auth.authenticate(token));
            server.stop();
            assertEquals(EmbeddedHttpServer.State.STOPPED, server.status().state());
            assertNull(server.status().address());
            assertFalse(server.menus().ready());
            assertThrows(dialogcanvas.web.auth.AuthException.class, () -> auth.authenticate(token));
            try (var rebound = reserve(port)) { assertEquals(port, rebound.getLocalPort()); }
            server.stop(); // Idempotent plugin cleanup.
        }
    }
}
