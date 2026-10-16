package dialogcanvas.web.menu;

import dialogcanvas.model.MenuJson;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MenuRoutes {
    private final MenuRepository menus;
    private final JsonMapper json = MenuJson.createMapper();
    public MenuRoutes(MenuRepository menus) { this.menus = menus; }
    public void register(JavalinConfig config) {
        config.routes.exception(ApiException.class, MenuRoutes::error);
        config.routes.get("/api/meta", context -> respond(context, new MenuMetadataApi().response(menus), 200));
        config.routes.get("/api/menus", context -> async(context, menus.list(), 200));
        config.routes.post("/api/menus/validate", context -> {
            JsonNode body = body(context, Set.of("menu")); async(context, menus.validate(menu(body)), 200);
        });
        config.routes.post("/api/menus/import-yaml", context -> {
            String type = context.contentType();
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/yaml"))
                throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "YAML 上传必须使用 application/yaml");
            async(context, menus.importYaml(context.bodyAsBytes()), 200);
        });
        config.routes.post("/api/menus/export-yaml", context -> {
            JsonNode body = body(context, Set.of("menu")); async(context, menus.exportYaml(menu(body)), 200);
        });
        config.routes.get("/api/menus/{id}", context -> async(context, menus.get(context.pathParam("id")), 200));
        config.routes.post("/api/menus", context -> {
            JsonNode body = body(context, Set.of("menu")); JsonNode menu = menu(body);
            context.attribute("dcmenu.audit.dispatched", true);
            context.future(() -> menus.create(menu, dialogcanvas.audit.AuditLogger.Origin.web()).handle((result, failure) -> {
                if (failure != null) failure(context, failure);
                else { context.header("Location", "/api/menus/" + result.get("menu").get("id").asString()); respond(context, result, 201); }
                return null;
            }));
        });
        config.routes.put("/api/menus/{id}", context -> {
            JsonNode body = body(context, Set.of("menu", "revision"));
            String revision = revision(body); JsonNode menu = menu(body); context.attribute("dcmenu.audit.dispatched", true);
            async(context, menus.update(context.pathParam("id"), revision, menu, dialogcanvas.audit.AuditLogger.Origin.web()), 200);
        });
        config.routes.delete("/api/menus/{id}", context -> {
            JsonNode body = body(context, Set.of("revision"));
            String revision = revision(body); context.attribute("dcmenu.audit.dispatched", true);
            async(context, menus.delete(context.pathParam("id"), revision, dialogcanvas.audit.AuditLogger.Origin.web()), 204);
        });
    }
    private JsonNode body(Context context, Set<String> allowed) {
        String contentType = context.contentType();
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "请求必须使用 application/json");
        }
        String raw = context.body(); JsonNode body;
        try { body = json.readTree(raw); } catch (RuntimeException failure) { throw new ApiException(400, "INVALID_JSON", "JSON 格式错误或包含重复属性"); }
        if (body == null || !body.isObject()) throw new ApiException(400, "INVALID_REQUEST", "请求体必须是对象");
        body.properties().forEach(entry -> { if (!allowed.contains(entry.getKey())) throw new ApiException(400, "INVALID_REQUEST", "请求包含未知管理字段"); });
        return body;
    }
    private static JsonNode menu(JsonNode body) {
        if (!body.has("menu")) throw ApiException.field("/menu", "REQUIRED", "menu 必填"); return body.get("menu");
    }
    private static String revision(JsonNode body) {
        if (!body.has("revision")) return null;
        if (!body.get("revision").isString()) throw new ApiException(400, "INVALID_REVISION", "revision 必须是字符串");
        return body.get("revision").asString();
    }
    private void async(Context context, CompletableFuture<?> future, int status) {
        context.future(() -> future.handle((result, failure) -> {
            if (failure != null) failure(context, failure);
            else if (status == 204) context.status(204);
            else respond(context, result, status);
            return null;
        }));
    }
    private void respond(Context context, Object result, int status) { context.contentType("application/json").status(status).result(json.writeValueAsString(result)); }
    private void failure(Context context, Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof ApiException known) error(known, context);
        else error(new ApiException(500, "INTERNAL_ERROR", "菜单请求处理失败"), context);
    }
    public static void error(ApiException failure, Context context) {
        context.attribute("dcmenu.audit.code", failure.code());
        context.attribute("dcmenu.api.error", true);
        var body = new java.util.LinkedHashMap<String, Object>(); body.put("error", failure.getMessage()); body.put("code", failure.code());
        if (!failure.details().isEmpty()) body.put("details", failure.details());
        context.status(failure.status()).contentType("application/json").result(MenuJson.createMapper().writeValueAsString(body));
    }
}
