package dialogcanvas.web;

import dialogcanvas.config.PluginConfig;
import dialogcanvas.model.MenuJson;
import dialogcanvas.web.auth.AuthException;
import dialogcanvas.web.auth.AuthMiddleware;
import dialogcanvas.web.auth.AuthService;
import dialogcanvas.web.auth.InitialPasswordConsole;
import dialogcanvas.web.menu.*;
import dialogcanvas.storage.FileStorage;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.net.BindException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Loopback-only authenticated HTTP service with menu CRUD, validation and metadata routes. */
public final class EmbeddedHttpServer implements AutoCloseable {
    private dialogcanvas.audit.AuditLogger audit = dialogcanvas.audit.AuditLogger.disabled();
    public void auditLogger(dialogcanvas.audit.AuditLogger audit) { this.audit = Objects.requireNonNull(audit); auth.auditLogger(audit); }
    private final PluginConfig config;
    private final Path configFile;
    private final AuthService auth;
    private final Consumer<String> initializationConsole;
    private final MenuRepository menus;
    private final JsonMapper json = MenuJson.createMapper().rebuild()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private Javalin server;
    private boolean started;
    private volatile boolean closed;
    private volatile boolean stopRequested;
    private final String resourceDirectory;
    private volatile Status status = new Status(State.NEW, null, "Web 服务尚未启动");

    public enum State { NEW, STARTING, RUNNING, FAILED, STOPPED }
    /** Address is present only after Jetty has successfully bound its connector. */
    public record Status(State state, String address, String diagnostic) {}

    public EmbeddedHttpServer(PluginConfig config, Path pluginDirectory, MenuRepository menus) {
        this(config, pluginDirectory, new AuthService(), InitialPasswordConsole::print, menus);
    }

    public EmbeddedHttpServer(PluginConfig config, Path pluginDirectory, AuthService auth,
                              Consumer<String> initializationConsole) {
        this(config, pluginDirectory, auth, initializationConsole, new MenuRepository(new FileStorage(config.dataDirectory()),
                new MenuValidator(new MenuEnvironment(java.util.Set.of(), java.util.Set.of()))));
    }

    public EmbeddedHttpServer(PluginConfig config, Path pluginDirectory, AuthService auth,
                              Consumer<String> initializationConsole, MenuRepository menus) {
        this(config, pluginDirectory, auth, initializationConsole, menus, "/web");
    }

    EmbeddedHttpServer(PluginConfig config, Path pluginDirectory, AuthService auth,
                       Consumer<String> initializationConsole, MenuRepository menus, String resourceDirectory) {
        this.config = Objects.requireNonNull(config);
        this.configFile = pluginDirectory.resolve("config.yml");
        this.auth = Objects.requireNonNull(auth);
        this.initializationConsole = Objects.requireNonNull(initializationConsole);
        this.menus = Objects.requireNonNull(menus);
        this.resourceDirectory = Objects.requireNonNull(resourceDirectory);
    }

    /** Never wait for this future on the Minecraft main thread. */
    public synchronized CompletableFuture<Void> startAsync() {
        if (started || closed) throw new IllegalStateException("HTTP service already initialized or closed");
        started = true;
        status = new Status(State.STARTING, null, "Web 服务正在初始化，请稍后重试");
        return auth.initialize(configFile, config.passwordHash(), initializationConsole)
                .thenCompose(ignored -> menus.initialize().exceptionally(failure -> {
                    java.util.logging.Logger.getLogger("DialogCanvas").warning("Menu service unavailable: check menu files while server is stopped.");
                    return null;
                })).thenRunAsync(() -> {
            synchronized (this) {
                if (closed) throw new IllegalStateException("HTTP service stopped during initialization");
                startListener();
            }
        }).whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                synchronized (this) {
                    if (!closed) {
                        status = new Status(State.FAILED, null, startupDiagnostic(failure));
                        closed = true;
                        releaseWebResources();
                    }
                }
            }
        });
    }

    private void startListener() {
        AuthMiddleware middleware = new AuthMiddleware(auth, config.port());
        server = Javalin.create(javalin -> {
            javalin.http.maxRequestSize = 1024 * 1024;
            EditorWebResources.configure(javalin, resourceDirectory);
            javalin.routes.before(middleware::before);
            javalin.routes.after(context -> {
                // Requests rejected before business dispatch (malformed body/CSRF etc.). Never inspect body/headers here.
                if (context.statusCode() < 400 || Boolean.TRUE.equals(context.attribute("dcmenu.audit.dispatched"))
                        || context.attribute(AuthMiddleware.SESSION_ATTRIBUTE) == null) return;
                String path = context.path(), method = context.method().name();
                dialogcanvas.audit.AuditLogger.Event event = null; String target = "<invalid-target>";
                if (method.equals("POST") && path.equals("/api/auth/password")) { event = dialogcanvas.audit.AuditLogger.Event.PASSWORD_CHANGE; target = "administrator-password"; }
                else if (method.equals("POST") && path.equals("/api/menus")) event = dialogcanvas.audit.AuditLogger.Event.MENU_CREATE;
                else if ((method.equals("PUT") || method.equals("DELETE")) && path.startsWith("/api/menus/") && path.indexOf('/', 11) < 0) {
                    event = method.equals("PUT") ? dialogcanvas.audit.AuditLogger.Event.MENU_UPDATE : dialogcanvas.audit.AuditLogger.Event.MENU_DELETE;
                    target = path.substring("/api/menus/".length());
                }
                if (event != null) audit.begin(event, dialogcanvas.audit.AuditLogger.Origin.web(), target)
                        .finish(dialogcanvas.audit.AuditLogger.Result.REJECTED, Objects.requireNonNullElse(context.<String>attribute("dcmenu.audit.code"), "HTTP_REJECTED"));
            });
            javalin.routes.exception(AuthException.class, (failure, context) -> error(context, failure));
            javalin.routes.exception(HttpResponseException.class, (failure, context) -> {
                String code = switch (failure.getStatus()) { case 404 -> "API_NOT_FOUND"; case 405 -> "METHOD_NOT_ALLOWED"; default -> "HTTP_" + failure.getStatus(); };
                if (isApi(context)) error(context, failure.getStatus(), code, "请求无法处理");
                else context.status(failure.getStatus());
            });
            javalin.routes.exception(Exception.class, (failure, context) -> {
                // Do not log request data or exception messages that could contain credentials.
                error(context, 500, "INTERNAL_ERROR", "请求处理失败");
            });
            javalin.routes.error(404, context -> {
                if (isApi(context) && !Boolean.TRUE.equals(context.attribute("dcmenu.api.error"))) error(context, 404, "API_NOT_FOUND", "接口不存在");
            });
            javalin.routes.error(405, context -> {
                if (isApi(context) && !Boolean.TRUE.equals(context.attribute("dcmenu.api.error"))) error(context, 405, "METHOD_NOT_ALLOWED", "HTTP 方法不支持");
            });
            javalin.routes.get("/health", context -> context.contentType("text/plain").result("ok"));
            javalin.routes.post("/api/auth/login", this::login);
            javalin.routes.post("/api/auth/password", this::changePassword);
            javalin.routes.post("/api/auth/logout", context -> {
                auth.logout(context.cookie(AuthMiddleware.COOKIE));
                context.header("Set-Cookie", AuthMiddleware.COOKIE + "=; Path=/api; Max-Age=0; HttpOnly; SameSite=Strict");
                context.status(204);
            });
            javalin.routes.get("/api/auth/session", context -> {
                AuthService.Session session = context.attribute(AuthMiddleware.SESSION_ATTRIBUTE);
                sessionResponse(context, session);
            });
            new MenuRoutes(menus).register(javalin);
        });
        // Javalin 7 checks ServiceLoader through the current thread's context loader.
        // Paper hides server provider resources from the plugin loader; reuse its logger
        // without bundling another SLF4J implementation or hiding plugin web resources.
        WebLoggingContext.start(() -> server.start("127.0.0.1", config.port()));
        if (!closed) status = new Status(State.RUNNING, "http://127.0.0.1:" + server.port() + "/", "Web 服务已启动");
    }

    private String startupDiagnostic(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BindException) {
                return "Web 服务启动失败：127.0.0.1 的端口 " + config.port()
                        + " 被占用或无法绑定；请释放端口或修改 config.yml 中的 port 后重启插件";
            }
        }
        return "Web 服务启动失败（端口 " + config.port() + "）：请检查端口、密码哈希和配置文件访问权限";
    }

    public Status status() {
        return stopRequested ? new Status(State.STOPPED, null, "Web 服务已停止") : status;
    }

    private void login(Context context) {
        String contentType = context.contentType();
        if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            throw new AuthException(415, "UNSUPPORTED_MEDIA_TYPE", "请求必须使用 application/json");
        }
        JsonNode body;
        // Access the body outside the parser catch so oversized requests retain HTTP 413.
        String raw = context.body();
        try { body = json.readTree(raw); }
        catch (RuntimeException failure) { throw new AuthException(400, "INVALID_REQUEST", "登录请求格式错误"); }
        if (body == null || !body.isObject() || body.size() != 1 || body.get("password") == null
                || !body.get("password").isString()) {
            throw new AuthException(400, "INVALID_REQUEST", "登录请求格式错误");
        }
        String password = body.get("password").asString();
        context.future(() -> auth.login(password).handle((login, failure) -> {
            if (failure != null) {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof AuthException known) error(context, known);
                else error(context, 503, "AUTH_UNAVAILABLE", "认证服务不可用");
            } else {
                // No Secure attribute on plain loopback HTTP. Never use URL or JS-readable storage for this token.
                context.header("Set-Cookie", AuthMiddleware.COOKIE + "=" + login.token()
                        + "; Path=/api; Max-Age=86400; HttpOnly; SameSite=Strict");
                sessionResponse(context, login.session());
            }
            return null;
        }));
    }

    private void sessionResponse(Context context, AuthService.Session session) {
        context.contentType("application/json").status(200).result(json.writeValueAsString(Map.of(
                "authenticated", true, "expiresAt", session.expiresAt().toString(), "csrfToken", session.csrfToken())));
    }

    private void changePassword(Context context) {
        String contentType = context.contentType();
        if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim()))
            throw new AuthException(415, "UNSUPPORTED_MEDIA_TYPE", "请求必须使用 application/json");
        String raw = context.body();
        JsonNode body;
        try { body = json.readTree(raw); }
        catch (RuntimeException failure) { throw new AuthException(400, "INVALID_REQUEST", "修改密码请求格式错误"); }
        if (body == null || !body.isObject() || body.size() != 3)
            throw new AuthException(400, "INVALID_REQUEST", "修改密码请求格式错误");
        for (String field : java.util.List.of("currentPassword", "newPassword", "confirmPassword"))
            if (body.get(field) == null || !body.get(field).isString()) throw new AuthException(400, "INVALID_REQUEST", "修改密码请求格式错误");
        context.attribute("dcmenu.audit.dispatched", true);
        context.future(() -> auth.changePassword(context.cookie(AuthMiddleware.COOKIE), body.get("currentPassword").asString(),
                body.get("newPassword").asString(), body.get("confirmPassword").asString()).handle((ignored, failure) -> {
            if (failure != null) {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof AuthException known) error(context, known);
                else error(context, 503, "AUTH_UNAVAILABLE", "认证服务不可用");
            } else {
                context.header("Set-Cookie", AuthMiddleware.COOKIE + "=; Path=/api; Max-Age=0; HttpOnly; SameSite=Strict");
                context.status(204);
            }
            return null;
        }));
    }

    private void error(Context context, AuthException failure) {
        if (failure.status() == 429) context.header("Retry-After", "60");
        error(context, failure.status(), failure.code(), failure.getMessage());
    }

    private void error(Context context, int status, String code, String message) {
        context.attribute("dcmenu.audit.code", code);
        context.attribute("dcmenu.api.error", true);
        context.contentType("application/json").status(status)
                .result(json.writeValueAsString(Map.of("error", message, "code", code)));
    }

    private static boolean isApi(Context context) {
        return AuthMiddleware.isApiPath(context.path());
    }

    public synchronized void stop() {
        stopRequested = true;
        closed = true;
        status = new Status(State.STOPPED, null, "Web 服务已停止");
        releaseWebResources();
    }

    /** Invalidate the address immediately; lock waits, auth commits and Jetty
     * connector joins happen on a worker, never in Minecraft onDisable. */
    public CompletableFuture<Void> stopAsync() {
        stopRequested = true;
        closed = true;
        status = new Status(State.STOPPED, null, "Web 服务已停止");
        return CompletableFuture.runAsync(this::stop).copy(); // Caller cancellation must not skip resource cleanup.
    }

    private void releaseWebResources() {
        auth.close(); // Invalidate sessions and complete pending login requests before stopping Jetty.
        CompletableFuture<Void> drained = menus.closeAsync();
        try {
            if (server != null) {
                Javalin listener = server;
                server = null;
                listener.stop();
            }
        } finally { drained.join(); } // Only the HTTP lifecycle worker waits for file commits.
    }

    public MenuRepository menus() { return menus; }

    @Override
    public void close() {
        stop();
    }
}
