package dialogcanvas.web;

import io.javalin.config.JavalinConfig;
import io.javalin.http.staticfiles.Location;

/** Serves only the packaged frontend directory, never the plugin's data directory. */
final class EditorWebResources {
    private EditorWebResources() {}

    static void configure(JavalinConfig config, String directory) {
        if (EditorWebResources.class.getResource(directory + "/index.html") != null) {
            // Reproducible JAR entries have fixed timestamps. A file timestamp/length ETag
            // can therefore identify different index bundles as equal after an upgrade.
            final String index;
            try (var stream = EditorWebResources.class.getResourceAsStream(directory + "/index.html")) {
                index = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            config.routes.get("/", context -> context.contentType("text/html; charset=utf-8")
                    .header("Cache-Control", "no-store").result(index));
            config.routes.get("/index.html", context -> context.contentType("text/html; charset=utf-8")
                    .header("Cache-Control", "no-store").result(index));
            config.staticFiles.add(files -> {
                files.directory = directory;
                files.hostedPath = "/";
                files.location = Location.CLASSPATH;
                files.headers = java.util.Map.of("Cache-Control", "no-store");
                // API paths always remain API routes, even if the bundle contains an api directory.
                files.skipFileFunction = request -> dialogcanvas.web.auth.AuthMiddleware.isApiPath(request.getRequestURI())
                        || request.getRequestURI().equals("/") || request.getRequestURI().equals("/index.html");
            });
        } else {
            config.routes.get("/", context -> context.contentType("text/html; charset=utf-8")
                    .header("Cache-Control", "no-store").result("""
                            <!doctype html>
                            <html lang="zh-CN"><head><meta charset="utf-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <title>DCMenu 本机编辑器</title></head>
                            <body><main><h1>DCMenu 本机编辑器</h1>
                            <h2>编辑器尚未安装完成</h2>
                            <p>本机 Web 服务已启动，但此 JAR 尚未包含前端编辑器资源。</p>
                            <p>当前页面仅提供说明，不能可视化编辑菜单。后续版本将内嵌编辑器。</p>
                            <p>请仅在服务器所在电脑访问。游戏测试命令仍可使用。</p>
                            </main></body></html>
                            """));
        }
    }
}
