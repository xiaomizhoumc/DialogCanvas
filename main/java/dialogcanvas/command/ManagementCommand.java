package dialogcanvas.command;

import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.engine.retention.PlayerDataRetention;
import dialogcanvas.web.EditorEntry;
import dialogcanvas.web.EmbeddedHttpServer;
import dialogcanvas.web.menu.ApiException;
import dialogcanvas.web.menu.MenuRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Public management commands; completion reads immutable registry IDs without file I/O. */
public final class ManagementCommand implements BasicCommand {
    private dialogcanvas.audit.AuditLogger audit = dialogcanvas.audit.AuditLogger.disabled();
    public void auditLogger(dialogcanvas.audit.AuditLogger audit) { this.audit = java.util.Objects.requireNonNull(audit); }
    private static final List<String> COMMANDS = List.of("help", "editor", "edit", "main", "list", "open", "reload", "data");
    private static final int PAGE_SIZE = 10;
    /** Called in the existing COMMANDS lifecycle, immediately before registration. No alias is forcefully taken. */
    public static List<String> registrationAliases(Predicate<String> occupied, Consumer<String> warning) {
        if (occupied.test("dme")) {
            warning.accept("命令 /dme 已被占用，跳过此别名；请继续使用 /dcmenu 或 /dialogcanvas。");
            return List.of("dialogcanvas");
        }
        return List.of("dialogcanvas", "dme");
    }
    private final Scheduler scheduler;
    private final Supplier<MenuRepository> repository;
    private final Supplier<EmbeddedHttpServer.Status> webStatus;
    private final BooleanSupplier ready;
    private final BiFunction<Player, String, CompletableFuture<Void>> open;
    private final Logger logger;
    private final Supplier<PlayerDataRetention> retention;

    public ManagementCommand(Scheduler scheduler, Supplier<MenuRepository> repository,
                             Supplier<EmbeddedHttpServer.Status> webStatus, BooleanSupplier ready,
                             BiFunction<Player, String, CompletableFuture<Void>> open, Logger logger) {
        this(scheduler, repository, webStatus, ready, open, logger, () -> null);
    }
    public ManagementCommand(Scheduler scheduler, Supplier<MenuRepository> repository,
                             Supplier<EmbeddedHttpServer.Status> webStatus, BooleanSupplier ready,
                             BiFunction<Player, String, CompletableFuture<Void>> open, Logger logger,
                             Supplier<PlayerDataRetention> retention) {
        this.scheduler = scheduler; this.repository = repository; this.webStatus = webStatus;
        this.ready = ready; this.open = open; this.logger = logger;
        this.retention = retention;
    }

    @Override public boolean canUse(CommandSender sender) {
        // Build 74 also invokes this from its asynchronous command-tree builder.
        // The root is public; executeOnMain enforces each subcommand's permissions.
        return true;
    }

    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!org.bukkit.Bukkit.isPrimaryThread() || source == null) return List.of();
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            var visible = COMMANDS.stream().filter(action -> permitted(sender, canonical(action))).toList();
            // Preserve ordinary shortcut users' minimal completion; help still works explicitly.
            if (!hasManagementPermission(sender)) visible = visible.stream().filter(action -> action.equals("main")).toList();
            return matching(visible, args.length == 0 ? "" : args[0]);
        }
        String action = canonical(args[0]);
        if (!permitted(sender, action) || !ready.getAsBoolean()) return List.of();
        if (args.length == 2 && "data".equalsIgnoreCase(args[0])) return matching(List.of("dry-run", "archive", "restore"), args[1]);
        if (args.length == 2 && (action.equals("open") || action.equals("reload") || action.equals("list"))) {
            MenuRepository menus = repository.get();
            if (menus == null || !menus.ready()) return List.of();
            List<String> ids = menus.completionIds();
            if (action.equals("list")) {
                int pages = ids.isEmpty() ? 1 : (ids.size() - 1) / PAGE_SIZE + 1;
                // Bound suggestions too; the user can still enter any valid page number.
                return matching(java.util.stream.IntStream.rangeClosed(1, Math.min(pages, 100)).mapToObj(Integer::toString).toList(), args[1]);
            }
            return matching(ids, args[1]);
        }
        if (args.length == 3 && action.equals("open")) {
            MenuRepository menus = repository.get();
            if (menus == null || !menus.ready()) return List.of();
            return matching(org.bukkit.Bukkit.getOnlinePlayers().stream().filter(Player::isOnline).map(Player::getName).toList(), args[2]);
        }
        return List.of();
    }

    private static String canonical(String action) { String value = action.toLowerCase(Locale.ROOT); return value.equals("edit") ? "editor" : value; }
    private static boolean hasManagementPermission(CommandSender sender) {
        return sender.hasPermission("dcmenu.admin") || List.of("help", "editor", "list", "open", "reload", "data").stream()
                .anyMatch(action -> sender.hasPermission("dcmenu.command." + action));
    }
    private static boolean permitted(CommandSender sender, String action) {
        if (sender.hasPermission("dcmenu.admin")) return true;
        if (action.equals("main")) return sender.hasPermission("dcmenu.shortcut");
        if (action.equals("help")) return sender.hasPermission("dcmenu.shortcut") || hasManagementPermission(sender);
        return COMMANDS.contains(action) && sender.hasPermission("dcmenu.command." + action);
    }

    private static List<String> matching(Collection<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).sorted().toList();
    }

    @Override public void execute(CommandSourceStack source, String[] args) {
        String[] captured = args.clone();
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        scheduler.sync(() -> { entered.set(true); executeOnMain(source.getSender(), captured); }).exceptionally(failure -> {
            if (!entered.get()) audit.begin(dialogcanvas.audit.AuditLogger.Event.MANAGEMENT_COMMAND,
                    dialogcanvas.audit.AuditLogger.Origin.system(), "dcmenu:dispatch").finish(dialogcanvas.audit.AuditLogger.Result.REJECTED, "SCHEDULING_REJECTED");
            logger.warning(dialogcanvas.feedback.ErrorFeedback.admin("MANAGEMENT_OPERATION_FAILED")); return null;
        });
    }

    private void executeOnMain(CommandSender sender, String[] args) {
        var origin = sender instanceof Player player ? new dialogcanvas.audit.AuditLogger.Origin("player", player.getUniqueId().toString())
                : sender instanceof org.bukkit.command.ConsoleCommandSender ? new dialogcanvas.audit.AuditLogger.Origin("console", "console")
                : dialogcanvas.audit.AuditLogger.Origin.system();
        String action = args.length == 0 ? "help" : canonical(args[0]);
        var operation = audit.begin(dialogcanvas.audit.AuditLogger.Event.MANAGEMENT_COMMAND, origin, "dcmenu:" + (COMMANDS.contains(action) ? action : "unknown"));
        try { executeAudited(sender, args, origin, operation); }
        catch (RuntimeException failure) { operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED, "COMMAND_FAILED"); throw failure; }
    }
    private void executeAudited(CommandSender sender, String[] args, dialogcanvas.audit.AuditLogger.Origin origin, dialogcanvas.audit.AuditLogger.Operation operation) {
        String action = args.length == 0 ? "help" : canonical(args[0]);
        if (!permitted(sender, action)) {
            operation.finish(dialogcanvas.audit.AuditLogger.Result.REJECTED, "PERMISSION_DENIED");
            reply(sender, "你没有使用菜单管理命令的权限。"); return;
        }
        if (!ready.getAsBoolean()) { operation.finish(dialogcanvas.audit.AuditLogger.Result.REJECTED, "PLUGIN_NOT_READY"); reply(sender, "插件尚未就绪，请稍后重试。"); return; }
        if (args.length == 0 || (args.length == 1 && "help".equalsIgnoreCase(args[0]))) {
            if (hasManagementPermission(sender)) help(sender);
            else reply(sender, "/dcmenu main — 打开快捷主菜单；也可潜行＋交换副手（默认 Shift＋F）。");
            operation.finish(dialogcanvas.audit.AuditLogger.Result.SUCCESS, "HELP_SHOWN");
            return;
        }
        if ("data".equals(action)) { dataCommand(sender, args, origin, operation); return; }
        if ("list".equals(action)) { list(sender, args, operation); return; }
        if ("main".equals(action)) {
            if (args.length != 1) { reject(operation, "INVALID_USAGE"); reply(sender, "用法：/dcmenu main"); return; }
            if (!(sender instanceof Player player)) { reject(operation, "PLAYER_REQUIRED"); reply(sender, "请在游戏内执行 /dcmenu main。"); return; }
            MenuRepository menus = available(sender); if (menus == null) { reject(operation, "MENU_SERVICE_UNAVAILABLE"); return; }
            String id = menus.mainMenuId();
            if (id == null) { reject(operation, "MAIN_MENU_NOT_SET"); reply(sender, "尚未设置快捷主菜单，请在网页菜单属性中勾选快捷打开并保存。"); }
            else display(sender, player, id, operation);
            return;
        }
        if ("editor".equals(action)) {
            if (args.length != 1) { reject(operation, "INVALID_USAGE"); reply(sender, "用法：/dcmenu editor"); return; }
            var status = webStatus.get(); EditorEntry.print(status, sender::sendMessage);
            operation.finish(status != null && status.state() == EmbeddedHttpServer.State.RUNNING ? dialogcanvas.audit.AuditLogger.Result.SUCCESS : dialogcanvas.audit.AuditLogger.Result.REJECTED,
                    status != null && status.state() == EmbeddedHttpServer.State.RUNNING ? "EDITOR_ADDRESS_SHOWN" : "WEB_UNAVAILABLE"); return;
        }
        if ("open".equals(action)) {
            if (args.length < 2 || args.length > 3) { reject(operation, "INVALID_USAGE"); reply(sender, "用法：/dcmenu open <菜单ID> [在线玩家]（按 Tab 选择已保存菜单和玩家）"); return; }
            Player target;
            if (args.length == 3) {
                target = org.bukkit.Bukkit.getPlayerExact(args[2]);
                if (target == null || !target.isOnline()) { reject(operation, "TARGET_NOT_ONLINE"); reply(sender, "找不到对应的在线玩家，请填写完整玩家名；不支持离线展示。"); return; }
            } else if (sender instanceof Player player) target = player;
            else { reject(operation, "TARGET_REQUIRED"); reply(sender, "控制台请使用 /dcmenu open <菜单ID> <在线玩家>；不指定玩家时请在游戏内执行。"); return; }
            if (!target.isOnline()) { reject(operation, "TARGET_NOT_ONLINE"); reply(sender, "目标玩家已离线，未打开菜单。"); return; }
            if (available(sender) != null) display(sender, target, args[1], operation); else reject(operation, "MENU_SERVICE_UNAVAILABLE");
            return;
        }
        if ("reload".equals(action)) {
            if (args.length > 2) { reject(operation, "INVALID_USAGE"); reply(sender, "用法：/dcmenu reload [菜单ID]；不填 ID 时逐项导入全部 YAML。"); return; }
            MenuRepository menus = available(sender); if (menus == null) { reject(operation, "MENU_SERVICE_UNAVAILABLE"); return; }
            boolean batch = args.length == 1;
            var work = batch ? menus.reloadAllYaml(origin) : menus.reloadYaml(args[1], origin);
            observe(operation, work);
            work.whenComplete((result, failure) -> scheduler.sync(() -> {
                if (failure != null) { failure(sender, failure); return; }
                if (batch) batchResult(sender, result);
                else { reply(sender, "YAML 正式数据重载成功：" + args[1]); warnings(sender, result.get("warnings")); }
            }));
            return;
        }
        reject(operation, "UNKNOWN_SUBCOMMAND"); reply(sender, "未知子命令，请输入 /dcmenu help 查看用法。");
    }

    private void list(CommandSender sender, String[] args, dialogcanvas.audit.AuditLogger.Operation operation) {
        int page = 1;
        if (args.length > 2) { reject(operation, "INVALID_USAGE"); reply(sender, "用法：/dcmenu list [页码]，每页 10 个菜单。"); return; }
        if (args.length == 2) {
            try { if (!args[1].matches("[0-9]{1,10}")) throw new NumberFormatException(); page = Integer.parseInt(args[1]); if (page < 1) throw new NumberFormatException(); }
            catch (NumberFormatException invalid) { reject(operation, "INVALID_PAGE"); reply(sender, "页码必须是从 1 开始的整数，例如 /dcmenu list 1。"); return; }
        }
        MenuRepository menus = available(sender); if (menus == null) { reject(operation, "MENU_SERVICE_UNAVAILABLE"); return; }
        List<String> ids = menus.completionIds(); int pages = ids.isEmpty() ? 1 : (ids.size() - 1) / PAGE_SIZE + 1;
        if (page > pages) { reject(operation, "PAGE_OUT_OF_RANGE"); reply(sender, "页码超出范围，当前共 " + pages + " 页；请使用 /dcmenu list 1。"); return; }
        if (ids.isEmpty()) { reply(sender, "尚无已加载的菜单；在网页新建保存，或通过 reload 导入 YAML。"); operation.finish(dialogcanvas.audit.AuditLogger.Result.SUCCESS, "LIST_EMPTY"); return; }
        reply(sender, "已加载菜单：第 " + page + "/" + pages + " 页，共 " + ids.size() + " 个（每页 10 个，按 ID 排序）。");
        int start = (page - 1) * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, ids.size()); index++) reply(sender, (index + 1) + ". " + ids.get(index));
        if (page < pages) reply(sender, "下一页：/dcmenu list " + (page + 1));
        operation.finish(dialogcanvas.audit.AuditLogger.Result.SUCCESS, "LIST_SHOWN");
    }

    /** Already inside Scheduler.sync. The supplied opener is the existing SavedMenuOpener -> DialogRenderer pipeline. */
    private void display(CommandSender sender, Player target, String id, dialogcanvas.audit.AuditLogger.Operation operation) {
        CompletableFuture<Void> work;
        try { work = java.util.Objects.requireNonNull(open.apply(target, id)); }
        catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        observe(operation, work);
        if (sender == target) return; // The existing opener reports errors to its own player.
        work.whenComplete((ignored, failure) -> scheduler.sync(() -> {
            if (failure == null) reply(sender, "已向在线玩家 " + target.getName() + " 展示菜单 " + id + "。");
            else {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (!target.isOnline()) reply(sender, "目标玩家已离线，菜单未展示。");
                else if (cause instanceof ApiException api) reply(sender, dialogcanvas.feedback.ErrorFeedback.admin(api.code()));
                else reply(sender, "菜单展示未完成或已取消，请核查目标玩家和服务端日志；未自动重试。");
            }
        }));
    }

    private MenuRepository available(CommandSender sender) {
        MenuRepository menus = repository.get();
        if (menus == null || !menus.ready()) { reply(sender, "菜单服务尚未就绪，请检查服务端启动日志。"); return null; }
        return menus;
    }
    private void dataCommand(CommandSender sender, String[] args, dialogcanvas.audit.AuditLogger.Origin origin, dialogcanvas.audit.AuditLogger.Operation operation) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) { reject(operation, "CONSOLE_REQUIRED"); reply(sender, "玩家数据维护仅允许在服务器控制台执行。"); return; }
        if (args.length < 2 || args.length > 3 || !List.of("dry-run", "archive", "restore").contains(args[1]) || args[1].equals("restore") && args.length != 3) {
            reject(operation, "INVALID_USAGE");
            reply(sender, "用法：dcmenu data dry-run [玩家UUID] / archive [玩家UUID] / restore <备份UUID>"); return;
        }
        PlayerDataRetention service = retention.get();
        if (service == null) { reject(operation, "RETENTION_UNAVAILABLE"); reply(sender, "玩家数据维护服务尚未就绪。"); return; }
        java.util.UUID id = null;
        if (args.length == 3) {
            try { id = java.util.UUID.fromString(args[2]); if (!id.toString().equals(args[2])) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException failure) { reject(operation, "INVALID_UUID"); reply(sender, "请填写完整的小写 UUID，不接受文件路径或玩家名字。"); return; }
        }
        reply(sender, "已提交后台维护；检查不改玩家文件，归档/恢复均先创建可恢复备份。");
        if (args[1].equals("restore")) {
          var work = service.restore(id, origin); observe(operation, work);
          work.whenComplete((result, failure) -> scheduler.sync(() -> {
            if (failure != null) retentionFailure(sender, failure);
            else reply(sender, result.status().equals("ALREADY_ORIGINAL") ? "原文件未被归档或已恢复，无需重复写入。"
                    : "恢复成功：" + result.player() + "；本次恢复前备份：" + result.safetyBackup());
          }));
        } else {
          var work = service.run(args[1].equals("archive") ? PlayerDataRetention.Mode.ARCHIVE : PlayerDataRetention.Mode.DRY_RUN, id, origin);
          observe(operation, work);
          work.whenComplete((report, failure) -> scheduler.sync(() -> {
                    if (failure != null) retentionFailure(sender, failure);
                    else PlayerDataRetention.describe(report).forEach(line -> reply(sender, line));
                }));
        }
    }
    private static void reject(dialogcanvas.audit.AuditLogger.Operation operation, String code) { operation.finish(dialogcanvas.audit.AuditLogger.Result.REJECTED, code); }
    private static void observe(dialogcanvas.audit.AuditLogger.Operation operation, CompletableFuture<?> work) {
        work.whenComplete((result, failure) -> {
            if (failure != null) {
                while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                String code = failure instanceof ApiException api ? api.code() : failure instanceof java.util.concurrent.CancellationException ? "OPERATION_CANCELLED"
                        : failure instanceof java.util.concurrent.RejectedExecutionException ? "PLUGIN_STOPPING" : "MANAGEMENT_OPERATION_FAILED";
                operation.finish(code.equals("MENU_STATE_UNCERTAIN") ? dialogcanvas.audit.AuditLogger.Result.UNCERTAIN : dialogcanvas.audit.AuditLogger.Result.FAILED, code);
            } else {
                boolean partial = result instanceof PlayerDataRetention.Report report && (report.failed() > 0 || report.interrupted());
                if (result instanceof JsonNode node) {
                    for (var item : node.path("items")) {
                        if (!item.path("success").asBoolean(true)) partial = true;
                        for (var warning : item.path("warnings")) if (warning.path("code").asString("").equals("YAML_SYNC_FAILED")) partial = true;
                    }
                    for (var warning : node.path("warnings")) if (warning.path("code").asString("").equals("YAML_SYNC_FAILED")) partial = true;
                }
                operation.finish(partial ? dialogcanvas.audit.AuditLogger.Result.PARTIAL : dialogcanvas.audit.AuditLogger.Result.SUCCESS, partial ? "MANAGEMENT_PARTIAL" : "MANAGEMENT_COMPLETED");
            }
        });
    }
    private void retentionFailure(CommandSender sender, Throwable failure) {
        String code = PlayerDataRetention.failureCode(failure);
        reply(sender, "维护未完成：" + PlayerDataRetention.reason(code) + "（" + code + "）。不会强制覆盖；请核查备份和原文件。");
    }

    private static void help(CommandSender sender) {
        reply(sender, "DialogCanvas 菜单管理");
        reply(sender, "/dcmenu help — 查看命令帮助");
        if (permitted(sender, "editor")) reply(sender, "/dcmenu editor（或 edit）— 只显示服务器本机编辑器地址，仍需登录");
        if (permitted(sender, "main")) reply(sender, "/dcmenu main — 打开已保存的快捷主菜单（也可潜行＋交换副手）");
        if (permitted(sender, "list")) reply(sender, "/dcmenu list [页码] — 分页查看已加载菜单，每页 10 个");
        if (permitted(sender, "open")) reply(sender, "/dcmenu open <菜单ID> [在线玩家] — 为自己或指定在线玩家展示菜单；控制台必须填玩家");
        if (permitted(sender, "reload")) {
            reply(sender, "/dcmenu reload <菜单ID> — 导入该菜单的手动 YAML 修改");
            reply(sender, "/dcmenu reload — 逐项导入全部 YAML，显示成功与失败结果");
        }
        if (permitted(sender, "data")) reply(sender, "控制台 dcmenu data dry-run [玩家UUID] — 检查失效限购；archive 备份归档；restore <备份UUID> 安全恢复");
        reply(sender, "管理命令使用 dcmenu.admin 或对应 dcmenu.command.* 授权；主菜单沿用 dcmenu.shortcut。");
        reply(sender, "按 Tab 补全允许使用的子命令、已加载菜单 ID 和在线玩家；/dme 仅在别名未被占用时可用。");
    }

    private static void batchResult(CommandSender sender, JsonNode result) {
        int success = 0, failed = 0;
        for (JsonNode item : result.get("items")) {
            if (item.get("success").asBoolean()) { success++; reply(sender, item.get("id").asString() + "：导入成功"); warnings(sender, item.get("warnings")); }
            else { failed++; reply(sender, item.get("id").asString() + "：导入失败，" + dialogcanvas.feedback.ErrorFeedback.admin(item.path("code").asString("MANAGEMENT_OPERATION_FAILED"))); details(sender, item.get("details")); }
        }
        reply(sender, "YAML 重载完成：成功 " + success + "，失败 " + failed + "。失败菜单保留上一份可用数据。");
    }

    private static void warnings(CommandSender sender, JsonNode warnings) {
        if (warnings == null || !warnings.isArray()) return;
        for (JsonNode warning : warnings) reply(sender, "重载提示：" + ("YAML_SYNC_FAILED".equals(warning.path("code").asString())
                ? dialogcanvas.feedback.ErrorFeedback.admin("YAML_SYNC_FAILED") : warning.path("message").asString("请检查 YAML 同步状态")));
    }

    private static void details(CommandSender sender, JsonNode details) {
        if (details == null || !details.isArray()) return;
        for (JsonNode detail : details) reply(sender, detail.path("path").asString() + "：" + detail.path("error").asString());
    }

    private void failure(CommandSender sender, Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof ApiException api) {
            reply(sender, "YAML 重载未完成：" + dialogcanvas.feedback.ErrorFeedback.admin(api.code()));
            for (var detail : api.details()) reply(sender, detail.path() + "：" + detail.error());
        } else { logger.warning("DCMenu YAML reload failed [MANAGEMENT_OPERATION_FAILED]"); reply(sender, dialogcanvas.feedback.ErrorFeedback.admin("MANAGEMENT_OPERATION_FAILED")); }
    }

    private static void reply(CommandSender sender, String text) {
        if (!(sender instanceof Player player) || player.isOnline()) sender.sendMessage("[DCMenu] " + text);
    }
}
