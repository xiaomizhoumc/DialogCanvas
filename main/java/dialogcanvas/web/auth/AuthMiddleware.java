package dialogcanvas.web.auth;

import io.javalin.http.Context;
import java.util.Set;

/** Applies to all API paths, including unknown routes and unsupported HTTP methods. */
public final class AuthMiddleware {
    public static final String COOKIE = "DCMENU_SESSION";
    public static final String SESSION_ATTRIBUTE = "dcmenu.auth.session";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final AuthService auth;
    private final String authority;
    private final String origin;
    private final int port;

    public AuthMiddleware(AuthService auth, int port) {
        this.auth = auth;
        this.port = port;
        authority = port == 80 ? "127.0.0.1" : "127.0.0.1:" + port;
        origin = "http://" + authority;
    }

    public void before(Context context) {
        String path = context.path();
        if (!isApiPath(path)) return;
        context.header("Cache-Control", "no-store");
        String method = context.method().name();
        boolean login = method.equals("POST") && path.equals("/api/auth/login");
        String host = context.header("Host");
        if (!authority.equals(host) && !(port == 80 && "127.0.0.1:80".equals(host))) {
            throw forbidden("ORIGIN_INVALID", "请求地址不受支持");
        }
        AuthService.Session session = null;
        if (!login) {
            session = auth.authenticate(context.cookie(COOKIE));
            context.attribute(SESSION_ATTRIBUTE, session);
        }
        String suppliedOrigin = context.header("Origin");
        if ((!SAFE_METHODS.contains(method) || suppliedOrigin != null) && !origin.equals(suppliedOrigin)) {
            throw forbidden("ORIGIN_INVALID", "请求必须来自编辑器同源页面");
        }
        if (!login && !SAFE_METHODS.contains(method)
                && !auth.validCsrf(session, context.header("X-CSRF-Token"))) {
            throw forbidden("CSRF_INVALID", "请求校验失败，请刷新页面后重试");
        }
    }

    private static AuthException forbidden(String code, String message) { return new AuthException(403, code, message); }

    /** Match encoded/canonical API paths too; only the literal login path is exempt from authentication. */
    public static boolean isApiPath(String rawPath) {
        try {
            String decoded = java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
            // Treat decoded spaces/#/? as path characters, never as URI delimiters.
            String path = new java.net.URI(null, null, decoded.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/"), null)
                    .normalize().getPath();
            return path.equals("/api") || path.startsWith("/api/");
        } catch (IllegalArgumentException | java.net.URISyntaxException failure) {
            throw new AuthException(400, "INVALID_REQUEST", "请求路径格式错误");
        }
    }
}
