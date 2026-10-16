package dialogcanvas.web.menu;

import dialogcanvas.dialog.TextRenderer;
import dialogcanvas.dialog.icon.VanillaIconResolver;
import dialogcanvas.engine.action.NumericExpression;
import dialogcanvas.engine.condition.ConditionEngine;
import dialogcanvas.model.*;
import dialogcanvas.storage.DocumentIds;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Shared structural, environment and business configuration validation; never executes a player action. */
public final class MenuValidator {
    public record Prepared(MenuDefinition menu, String json, List<MenuRegistry.Warning> warnings, Set<String> references) {}
    private static final Pattern INPUT_REFERENCE = Pattern.compile("\\$\\(([A-Za-z0-9_.-]+)\\)");
    private final JsonMapper mapper = MenuJson.createMapper();
    private final TextRenderer texts = new TextRenderer();
    private final ConditionEngine conditions = new ConditionEngine(Logger.getLogger("DialogCanvas"));
    private final MenuEnvironment environment;
    private volatile boolean productionConnected;
    private volatile String economyProvider = "未连接";
    public MenuValidator(MenuEnvironment environment) { this.environment = Objects.requireNonNull(environment); }
    public MenuEnvironment environment() { return environment; }
    public void productionConnected(boolean connected) { productionConnected = connected; }
    public boolean productionConnected() { return productionConnected; }
    public void economyProvider(String provider) { economyProvider = provider; }
    public String economyProvider() { return economyProvider; }

    public Prepared prepare(JsonNode document, Set<String> knownMenus) {
        if (document == null || !document.isObject()) fail("/menu", "TYPE_MISMATCH", "menu 必须是对象");
        checkTree(document, "/menu", 1);
        MenuDefinition menu;
        try { menu = mapper.treeToValue(document, MenuDefinition.class); }
        catch (JacksonException failure) {
            StringBuilder path = new StringBuilder("/menu");
            for (var reference : failure.getPath()) {
                if (reference.getPropertyName() != null) path.append('/').append(escape(reference.getPropertyName()));
                else if (reference.getIndex() >= 0) path.append('/').append(reference.getIndex());
            }
            throw ApiException.field(path.toString(), "INVALID_FIELD", "字段未知、类型错误或内容无法解析");
        } catch (RuntimeException failure) { throw ApiException.field("/menu", "INVALID_MODEL", "菜单模型无法解析"); }
        if (!DocumentIds.valid(menu.getId())) fail("/menu/id", "INVALID_MENU_ID", "ID 必须为 1–64 位小写字母、数字、_ 或 -，且不能是保留名");
        if (!Integer.valueOf(1).equals(menu.getSchemaVersion())) fail("/menu/schemaVersion", "UNSUPPORTED_VERSION", "schemaVersion 必须为 1");
        text(menu.getTitle(), "/menu/title", true); text(menu.getExternalTitle(), "/menu/externalTitle", false);
        if (menu.getMetadata() != null) {
            var metadata = menu.getMetadata();
            if (metadata.getVersion() != null && metadata.getVersion() < 1) fail("/menu/metadata/version", "OUT_OF_RANGE", "version 必须为正整数");
            if (metadata.getCreated() != null) timestamp(metadata.getCreated(), "/menu/metadata/created");
            if (metadata.getUpdated() != null) timestamp(metadata.getUpdated(), "/menu/metadata/updated");
        }
        if (menu.getDialogType() == null) fail("/menu/dialogType", "REQUIRED", "dialogType 必填");
        if (!Set.of(DialogType.NOTICE, DialogType.CONFIRMATION, DialogType.MULTI_ACTION).contains(menu.getDialogType())) {
            fail("/menu/dialogType", "UNSUPPORTED_CAPABILITY", "当前渲染器仅支持 notice、confirmation、multi_action");
        }
        if (menu.getDialogs() != null || menu.getButtonWidth() != null) fail("/menu/dialogs", "UNSUPPORTED_CAPABILITY", "当前不支持 Dialog 列表引用和 buttonWidth");
        List<MenuRegistry.Warning> warnings = new ArrayList<>(); Set<String> references = new HashSet<>();
        if (Boolean.TRUE.equals(menu.getPause())) warnings.add(new MenuRegistry.Warning("/menu/pause", "SERVER_PAUSE_IGNORED",
                "这是服务端插件，不会暂停游戏；旧配置 pause=true 保留但不生效"));
        var inputs = new HashMap<String, InputDefinition>();
        var inputList = orEmpty(menu.getInputs());
        for (int i = 0; i < inputList.size(); i++) {
            InputDefinition input = inputList.get(i); String path = "/menu/inputs/" + i;
            if (input == null) fail(path, "REQUIRED", "输入不能为 null");
            key(input.getKey(), path + "/key");
            if (inputs.putIfAbsent(input.getKey(), input) != null) fail(path + "/key", "DUPLICATE_KEY", "输入 key 重复");
            input(input, path);
        }
        var body = orEmpty(menu.getBody());
        for (int i = 0; i < body.size(); i++) body(body.get(i), "/menu/body/" + i);
        Map<String, ActionDefinition> buttons = new HashMap<>();
        switch (menu.getDialogType()) {
            case NOTICE -> {
                unrelated(menu.getYesButton() != null || menu.getNoButton() != null || menu.getActions() != null
                        || menu.getExitAction() != null || menu.getColumns() != null, "/menu/actions");
                if (menu.getNoticeAction() != null) button(menu.getNoticeAction(), "/menu/noticeAction", inputs, buttons, references, warnings);
            }
            case CONFIRMATION -> {
                unrelated(menu.getNoticeAction() != null || menu.getActions() != null || menu.getExitAction() != null || menu.getColumns() != null, "/menu/actions");
                button(menu.getYesButton(), "/menu/yesButton", inputs, buttons, references, warnings);
                button(menu.getNoButton(), "/menu/noButton", inputs, buttons, references, warnings);
            }
            case MULTI_ACTION -> {
                unrelated(menu.getNoticeAction() != null || menu.getYesButton() != null || menu.getNoButton() != null, "/menu/noticeAction");
                range(menu.getColumns(), 1, Integer.MAX_VALUE, "/menu/columns");
                var actions = orEmpty(menu.getActions());
                if (actions.isEmpty()) fail("/menu/actions", "REQUIRED", "multi_action 至少需要一个按钮");
                for (int i = 0; i < actions.size(); i++) button(actions.get(i), "/menu/actions/" + i, inputs, buttons, references, warnings);
                if (menu.getExitAction() != null) button(menu.getExitAction(), "/menu/exitAction", inputs, buttons, references, warnings);
            }
            default -> throw new IllegalStateException();
        }
        for (String reference : references) if (!reference.equals(menu.getId()) && !knownMenus.contains(reference)) {
            // Report the actual action field, including references in denial branches.
            referencePaths(mapper.valueToTree(menu), "/menu", reference).forEach(path -> fail(path, "MENU_REFERENCE_NOT_FOUND", "引用的菜单不存在"));
        }
        return new Prepared(menu, mapper.writeValueAsString(menu), List.copyOf(warnings), Set.copyOf(references));
    }

    private void body(BodyBlock block, String path) {
        if (block == null) fail(path, "REQUIRED", "正文不能为 null");
        switch (block) {
            case BodyBlock.Text value -> {
                if (!"text".equals(value.getType())) fail(path + "/type", "INVALID_TYPE", "文本正文 type 必须为 text");
                text(value.getText(), path + "/text", true); range(value.getWidth(), 1, 1024, path + "/width");
                if (value.getColorMode() != null || value.getColor() != null) fail(path + "/colorMode", "UNSUPPORTED_CAPABILITY", "请使用 TextSpec 配置颜色");
            }
            case BodyBlock.Item value -> {
                material(value.getMaterial(), path + "/material"); range(value.getAmount(), 1, Integer.MAX_VALUE, path + "/amount");
                text(value.getName(), path + "/name", false); texts(value.getLore(), path + "/lore");
                range(value.getWidth(), 1, 256, path + "/width"); range(value.getHeight(), 1, 256, path + "/height");
                if (value.getDescription() != null) body(value.getDescription(), path + "/description");
            }
            default -> fail(path, "UNSUPPORTED_CAPABILITY", "未知正文类型");
        }
    }

    private void input(InputDefinition input, String path) {
        text(input.getLabel(), path + "/label", true);
        switch (input) {
            case InputDefinition.Bool ignored -> {}
            case InputDefinition.Text value -> {
                range(value.getWidth(), 1, 1024, path + "/width"); range(value.getMaxLength(), 1, Integer.MAX_VALUE, path + "/maxLength");
                int max = value.getMaxLength() == null ? 32 : value.getMaxLength();
                if (value.getInitial() != null && value.getInitial().length() > max) fail(path + "/initial", "OUT_OF_RANGE", "初始文字超过 maxLength");
                boolean numeric = Boolean.TRUE.equals(value.getNumeric());
                if (!numeric && (value.getInteger() != null || value.getMinimum() != null || value.getMaximum() != null
                        || value.getInventoryMaximumMaterial() != null)) {
                    fail(path + "/numeric", "UNRELATED_FIELD", "数字范围和背包上限只能用于 numeric=true 的文本输入");
                }
                if (numeric) {
                    double minimum = finite(value.getMinimum(), path + "/minimum", -Double.MAX_VALUE);
                    double maximum = finite(value.getMaximum(), path + "/maximum", Double.MAX_VALUE);
                    if (minimum > maximum) fail(path + "/maximum", "OUT_OF_RANGE", "maximum 不得小于 minimum");
                    if (value.getInitial() != null) {
                        double initial;
                        try { initial = Double.parseDouble(value.getInitial().trim()); }
                        catch (RuntimeException failure) { initial = Double.NaN; }
                        if (!Double.isFinite(initial) || initial < minimum || initial > maximum
                                || (Boolean.TRUE.equals(value.getInteger()) && initial != Math.floor(initial))) {
                            fail(path + "/initial", "INVALID_NUMBER", "数字初始值必须满足整数和范围限制");
                        }
                    }
                    if (value.getMultiline() != null) fail(path + "/multiline", "UNRELATED_FIELD", "数字输入不使用多行文本");
                    if (value.getInventoryMaximumMaterial() != null) material(value.getInventoryMaximumMaterial(), path + "/inventoryMaximumMaterial");
                }
                if (value.getMultiline() != null) {
                    range(value.getMultiline().getMaxLines(), 1, Integer.MAX_VALUE, path + "/multiline/maxLines");
                    range(value.getMultiline().getHeight(), 1, 512, path + "/multiline/height");
                }
            }
            case InputDefinition.NumberRange value -> {
                float start = number(value.getStart(), path + "/start"), end = number(value.getEnd(), path + "/end");
                if (start > end) fail(path + "/end", "OUT_OF_RANGE", "end 不得小于 start");
                if (value.getInitial() != null) {
                    float initial = number(value.getInitial(), path + "/initial");
                    if (initial < start || initial > end) fail(path + "/initial", "OUT_OF_RANGE", "initial 超出范围");
                }
                if (value.getStep() != null && number(value.getStep(), path + "/step") <= 0) fail(path + "/step", "OUT_OF_RANGE", "step 必须大于 0");
                range(value.getWidth(), 1, 1024, path + "/width");
                if (value.getInventoryMaximumMaterial() != null) material(value.getInventoryMaximumMaterial(), path + "/inventoryMaximumMaterial");
                if (value.getLabelFormat() != null) try { String.format(Locale.ROOT, value.getLabelFormat(), "label", 1.0); }
                catch (RuntimeException failure) { fail(path + "/labelFormat", "INVALID_FORMAT", "无效的 labelFormat"); }
            }
            case InputDefinition.SingleOption value -> {
                range(value.getWidth(), 1, 1024, path + "/width"); var seen = new HashSet<String>(); int selected = 0;
                var options = orEmpty(value.getOptions()); if (options.isEmpty()) fail(path + "/options", "REQUIRED", "options 不能为空");
                for (int i = 0; i < options.size(); i++) {
                    var option = options.get(i); String p = path + "/options/" + i;
                    if (option == null) fail(p, "REQUIRED", "选项不能为 null");
                    key(option.getId(), p + "/id"); if (!seen.add(option.getId())) fail(p + "/id", "DUPLICATE_KEY", "选项 ID 重复");
                    text(option.getDisplay(), p + "/display", false);
                    if (Boolean.TRUE.equals(option.getInitial()) && ++selected > 1) fail(p + "/initial", "DUPLICATE_INITIAL", "最多一个初始选项");
                }
            }
            default -> fail(path, "UNSUPPORTED_CAPABILITY", "未知输入类型");
        }
    }

    private void button(ActionDefinition button, String path, Map<String, InputDefinition> inputs,
                        Map<String, ActionDefinition> ids, Set<String> references, List<MenuRegistry.Warning> warnings) {
        if (button == null) fail(path, "REQUIRED", "按钮必填");
        key(button.getId(), path + "/id"); if (ids.putIfAbsent(button.getId(), button) != null) fail(path + "/id", "DUPLICATE_KEY", "按钮 ID 重复");
        if (button.getType() != null && !"button".equals(button.getType())) fail(path + "/type", "INVALID_TYPE", "按钮 type 必须为 button");
        text(button.getLabel(), path + "/label", true); text(button.getTooltip(), path + "/tooltip", false);
        range(button.getWidth(), 1, 1024, path + "/width");
        String kind = button.getActionType() == null ? "custom" : button.getActionType();
        if (!Set.of("custom", "close", "none").contains(kind)) fail(path + "/actionType", "UNSUPPORTED_CAPABILITY", "当前仅支持 custom、close、none");
        if (button.getTemplate() != null || button.getClickEvent() != null || button.getAdditions() != null) fail(path, "UNSUPPORTED_CAPABILITY", "当前按钮不支持模板、静态点击事件或 additions");
        if (button.getActionId() != null && !button.getActionId().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) fail(path + "/actionId", "INVALID_KEY", "actionId 必须为命名空间键");
        if (button.getCallbackOptions() != null) {
            range(button.getCallbackOptions().getUses(), 1, Integer.MAX_VALUE, path + "/callbackOptions/uses");
            if (button.getCallbackOptions().getLifetime() != null) try {
                Duration lifetime = Duration.parse(button.getCallbackOptions().getLifetime());
                if (lifetime.isZero() || lifetime.isNegative()) throw new IllegalArgumentException(); lifetime.toMillis();
            } catch (RuntimeException failure) { fail(path + "/callbackOptions/lifetime", "INVALID_DURATION", "有效期必须为正 ISO-8601 Duration"); }
        }
        var icon = new VanillaIconResolver().resolve(button.getIcon());
        if (icon.error() != null) warnings.add(new MenuRegistry.Warning(path + "/icon", "ICON_UNMAPPED", "无效或未映射图标，显示时保留按钮文字"));
        var checked = conditions.validate(button.getConditions());
        if (!checked.passed()) {
            String suffix = checked.path().substring("conditions".length()).replaceAll("\\[(\\d+)\\]", "/$1").replace('.', '/');
            fail(path + "/conditions" + suffix, checked.errorCode(), "条件配置不合法");
        }
        checkInputSources(mapper.valueToTree(button.getConditions()), path + "/conditions", inputs);
        steps(button.getActions(), path + "/actions", inputs, references); steps(button.getDenyActions(), path + "/denyActions", inputs, references);
        if (button.getLimit() != null) for (var entry : button.getLimit().entrySet()) {
            String p = path + "/limit/" + escape(entry.getKey()); key(entry.getKey(), p);
            LimitDefinition limit = entry.getValue(); if (limit == null) fail(p, "REQUIRED", "限购配置不能为空");
            if (limit.getScope() != null) key(limit.getScope(), p + "/scope");
            if (limit.getPlayer() != null && limit.getPlayer() < -1) fail(p + "/player", "OUT_OF_RANGE", "player 必须为 -1 或非负整数");
            if (limit.getServer() != null && limit.getServer() != -1) fail(p + "/server", "UNSUPPORTED_CAPABILITY", "服务器维度限购尚未实现，server 只能为 -1");
            if (limit.getReset() == null || !Set.of("DAILY", "WEEKLY", "MONTHLY", "NEVER", "TIMED").contains(limit.getReset())) fail(p + "/reset", "INVALID_RESET", "无效重置模式");
            if ("TIMED".equals(limit.getReset())) {
                if (limit.getTimedSeconds() == null || limit.getTimedSeconds() <= 0) fail(p + "/timedSeconds", "OUT_OF_RANGE", "TIMED 需要正数秒间隔");
                try { java.time.Instant.now().plusSeconds(limit.getTimedSeconds()); } catch (RuntimeException failure) { fail(p + "/timedSeconds", "OUT_OF_RANGE", "时间间隔溢出"); }
            } else if (limit.getTimedSeconds() != null) fail(p + "/timedSeconds", "UNRELATED_FIELD", "非 TIMED 不使用 timedSeconds");
        }
        if (button.getCooldown() != null) for (var entry : button.getCooldown().entrySet()) {
            String p = path + "/cooldown/" + escape(entry.getKey()); key(entry.getKey(), p);
            Double seconds = entry.getValue();
            if (seconds == null || !Double.isFinite(seconds) || seconds < 0 || seconds != Math.floor(seconds) || seconds >= Long.MAX_VALUE) fail(p, "OUT_OF_RANGE", "冷却必须为非负整数秒");
            try { java.time.Instant.now().plusSeconds(seconds.longValue()); } catch (RuntimeException failure) { fail(p, "OUT_OF_RANGE", "冷却时间溢出"); }
        }
        if (!productionConnected && (button.getConditions() != null || !orEmpty(button.getActions()).isEmpty() || !orEmpty(button.getDenyActions()).isEmpty()
                || button.getLimit() != null || button.getCooldown() != null || Boolean.TRUE.equals(button.getRefreshAfterAction()))) {
            warnings.add(new MenuRegistry.Warning(path, "BUSINESS_NOT_CONNECTED", "逻辑引擎已实现，但生产按钮链路尚未接入"));
        }
        if (productionConnected && "未连接".equals(economyProvider) && java.util.stream.Stream.concat(orEmpty(button.getActions()).stream(), orEmpty(button.getDenyActions()).stream())
                .anyMatch(step -> step instanceof ActionStepDefinition.EconomyWithdraw || step instanceof ActionStepDefinition.EconomyDeposit))
            warnings.add(new MenuRegistry.Warning(path, "ECONOMY_UNAVAILABLE", "按钮已接入动作引擎，但没有可用的 Vault 经济服务；扣款或充值会被拒绝，请检查经济插件"));
        if ((button.getLimit() != null && !button.getLimit().isEmpty()) && button.getActions() != null) {
            boolean ended = false;
            for (int i = 0; i < button.getActions().size(); i++) {
                var step = button.getActions().get(i);
                if (step instanceof ActionStepDefinition.Return) ended = true;
                if (step instanceof ActionStepDefinition.GiveItem && (ended || (step.getChance() != null && step.getChance() != 1)))
                    fail(path + "/actions/" + i, "UNSUPPORTED_LIMIT_QUANTITY", "限购发货必须必定执行，不能设随机概率或放在 return 后；限购按实际配置的发货总数量计数");
            }
        }
    }

    private void steps(List<ActionStepDefinition> actions, String path, Map<String, InputDefinition> inputs, Set<String> references) {
        Set<String> numeric = new HashSet<>(); inputs.forEach((key, value) -> {
            if (value instanceof InputDefinition.NumberRange || value instanceof InputDefinition.Text text && Boolean.TRUE.equals(text.getNumeric())) numeric.add(key);
        });
        var list = orEmpty(actions);
        for (int i = 0; i < list.size(); i++) {
            var action = list.get(i); String p = path + "/" + i;
            if (action == null) fail(p, "REQUIRED", "动作不能为空");
            if (action.getDelay() != null && (!Double.isFinite(action.getDelay()) || action.getDelay() < 0
                    || action.getDelay() != Math.floor(action.getDelay()) || action.getDelay() >= Long.MAX_VALUE)) fail(p + "/delay", "OUT_OF_RANGE", "delay 必须为非负整数 tick");
            if (action.getChance() != null && (!Double.isFinite(action.getChance()) || action.getChance() < 0 || action.getChance() > 1)) fail(p + "/chance", "OUT_OF_RANGE", "chance 必须在 0–1 范围");
            switch (action) {
                case ActionStepDefinition.Message value -> {
                    text(value.getText(), p + "/text", true);
                    try { dialogcanvas.engine.action.ActionMessageTemplate.validate(value.getText(), numeric); }
                    catch (IllegalArgumentException failure) { fail(p + "/text", "INVALID_EXPRESSION", "消息中的数量或金额引用不合法；请使用数值输入标识，例如 $(buy_text)，总价可写 $(1 * $(buy_text))"); }
                }
                case ActionStepDefinition.EconomyDeposit value -> expression(value.getAmount(), p + "/amount", numeric, true, false);
                case ActionStepDefinition.EconomyWithdraw value -> expression(value.getAmount(), p + "/amount", numeric, true, false);
                case ActionStepDefinition.PlayerDataIncrement value -> { key(value.getKey(), p + "/key"); expression(value.getAmount(), p + "/amount", numeric, false, false); }
                case ActionStepDefinition.PlayerDataSet value -> {
                    key(value.getKey(), p + "/key"); if (value.getValue() == null) fail(p + "/value", "REQUIRED", "value 必填");
                }
                case ActionStepDefinition.GiveItem value -> { itemConfiguration(value, p); expression(value.getAmount(), p + "/amount", numeric, true, true); }
                case ActionStepDefinition.TakeItem value -> { itemConfiguration(value, p); expression(value.getAmount(), p + "/amount", numeric, true, true); }
                case ActionStepDefinition.PlaySound value -> {
                    if (value.getSound() == null || !environment.sounds().contains(value.getSound())) fail(p + "/sound", "UNKNOWN_SOUND", "声音不存在于已捕获的服务端能力集合");
                    if (value.getVolume() != null && number(value.getVolume(), p + "/volume") < 0) fail(p + "/volume", "OUT_OF_RANGE", "volume 必须非负");
                    if (value.getPitch() != null && number(value.getPitch(), p + "/pitch") < 0) fail(p + "/pitch", "OUT_OF_RANGE", "pitch 必须非负");
                }
                case ActionStepDefinition.Command value -> {
                    if (value.getCommand() == null || !value.getCommand().matches("[a-z0-9_.-]+(?::[a-z0-9_.-]+)?")) fail(p + "/command", "UNSAFE_COMMAND", "命令名称必须为固定小写标识，不含 / 或参数");
                    if (value.getExecutor() != null && !dialogcanvas.engine.action.CommandArguments.EXECUTORS.contains(value.getExecutor())) fail(p + "/executor", "INVALID_EXECUTOR", "请选择玩家、OP 或控制台执行命令（player / op / console）");
                    var arguments = orEmpty(value.getArguments());
                    for (int j = 0; j < arguments.size(); j++) {
                        String argument = arguments.get(j); if (!dialogcanvas.engine.action.CommandArguments.literal(argument)) fail(p + "/arguments/" + j, "UNSAFE_COMMAND", "参数不能为空、超过 2048 字符或含换行及控制字符；每行填写一个参数");
                        var matcher = INPUT_REFERENCE.matcher(argument);
                        while (matcher.find()) if (!inputs.containsKey(matcher.group(1))) fail(p + "/arguments/" + j, "INPUT_NOT_FOUND", "命令引用的输入不存在");
                    }
                    if (value.getCommand().length() + arguments.stream().mapToInt(s -> s.length() + 1).sum() > 8192)
                        fail(p + "/arguments", "UNSAFE_COMMAND", "整条命令不能超过 8192 字符");
                }
                case ActionStepDefinition.OpenMenu value -> {
                    if (!DocumentIds.valid(value.getMenuId())) fail(p + "/menuId", "INVALID_MENU_ID", "引用菜单 ID 不合法");
                    references.add(value.getMenuId());
                }
                case ActionStepDefinition.CloseMenu ignored -> {}
                case ActionStepDefinition.RefreshMenu ignored -> {}
                case ActionStepDefinition.Return ignored -> {}
                default -> fail(p, "UNSUPPORTED_CAPABILITY", "未知动作类型");
            }
        }
    }

    private void expression(String expression, String path, Set<String> inputs, boolean positive, boolean integer) {
        try {
            NumericExpression.validate(expression, inputs);
            if (!expression.contains("$(")) {
                double value = NumericExpression.evaluate(expression, Map.of());
                if ((positive && value <= 0) || (integer && (value != Math.floor(value) || value > Integer.MAX_VALUE))) throw new IllegalArgumentException();
            }
        } catch (RuntimeException failure) { fail(path, "INVALID_EXPRESSION", "数值表达式、常量范围或输入引用不合法"); }
    }
    private void checkInputSources(JsonNode node, String path, Map<String, InputDefinition> inputs) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            if (node.has("source") && "input".equals(node.get("source").asString()) && (!node.has("key") || !inputs.containsKey(node.get("key").asString()))) fail(path + "/key", "INPUT_NOT_FOUND", "条件引用的输入不存在");
            node.properties().forEach(entry -> checkInputSources(entry.getValue(), path + "/" + escape(entry.getKey()), inputs));
        } else if (node.isArray()) for (int i = 0; i < node.size(); i++) checkInputSources(node.get(i), path + "/" + i, inputs);
    }
    public static List<String> referencePaths(JsonNode node, String path, String target) {
        var result = new ArrayList<String>();
        for (String name : List.of("noticeAction", "yesButton", "noButton", "exitAction")) {
            if (node.has(name)) buttonReferencePaths(node.get(name), path + "/" + name, target, result);
        }
        JsonNode buttons = node.get("actions");
        if (buttons != null && buttons.isArray()) for (int i = 0; i < buttons.size(); i++) buttonReferencePaths(buttons.get(i), path + "/actions/" + i, target, result);
        return result;
    }
    private static void buttonReferencePaths(JsonNode button, String path, String target, List<String> result) {
        if (button == null || !button.isObject()) return;
        for (String branch : List.of("actions", "denyActions")) {
            JsonNode actions = button.get(branch); if (actions == null || !actions.isArray()) continue;
            for (int i = 0; i < actions.size(); i++) {
                JsonNode action = actions.get(i);
                if (action.isObject() && action.has("type") && "open_menu".equals(action.get("type").asString())
                        && action.has("menuId") && target.equals(action.get("menuId").asString())) result.add(path + "/" + branch + "/" + i + "/menuId");
            }
        }
    }
    private void itemConfiguration(ItemActionDefinition value, String path) {
        material(value.getMaterial(), path + "/material");
        text(value.getName(), path + "/name", false); texts(value.getLore(), path + "/lore");
        if (value.getEnchantments() != null) {
            if (value.getEnchantments().size() > 32) fail(path + "/enchantments", "TOO_MANY", "最多配置 32 项附魔");
            var seen = new HashSet<String>();
            for (var entry : value.getEnchantments().entrySet()) {
                String pointer = path + "/enchantments/" + entry.getKey().replace("~", "~0").replace("/", "~1");
                String key = registryId(entry.getKey());
                if (!environment.enchantments().contains(key)) fail(pointer, "UNKNOWN_ENCHANTMENT", "找不到附魔注册名；例如 minecraft:sharpness，具体支持项以当前服务器为准");
                if (!seen.add(key)) fail(pointer, "DUPLICATE_ENCHANTMENT", "同一个附魔不能同时用简写和完整注册名配置两次");
                if (entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 255) fail(pointer, "OUT_OF_RANGE", "附魔等级须为 1～255 的整数；允许服主显式配置超过原版上限的等级");
            }
        }
        if (value.getAttributes() != null) {
            if (value.getAttributes().size() > 32) fail(path + "/attributes", "TOO_MANY", "最多配置 32 项属性");
            for (int i = 0; i < value.getAttributes().size(); i++) {
                var attribute = value.getAttributes().get(i); String p = path + "/attributes/" + i;
                if (attribute == null) { fail(p, "REQUIRED", "属性条目不能为空"); continue; }
                if (!environment.attributes().contains(registryId(attribute.getAttribute()))) fail(p + "/attribute", "UNKNOWN_ATTRIBUTE", "找不到属性注册名；例如 minecraft:attack_damage");
                if (attribute.getAmount() == null || !Double.isFinite(attribute.getAmount()) || Math.abs(attribute.getAmount()) > 1_000_000) fail(p + "/amount", "OUT_OF_RANGE", "属性数值必须为有限数字，绝对值不超过 1000000");
                if (attribute.getOperation() != null && !Set.of("ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1").contains(attribute.getOperation())) fail(p + "/operation", "INVALID_OPERATION", "请选择直接增加、基础值比例增加或最终乘算");
                if (attribute.getSlot() != null && !Set.of("any", "mainhand", "offhand", "hand", "feet", "legs", "chest", "head", "armor", "body", "saddle").contains(attribute.getSlot())) fail(p + "/slot", "INVALID_SLOT", "属性生效部位不支持；例如 mainhand 表示主手持有");
            }
        }
    }

    private static String registryId(String value) {
        if (value == null) return "";
        String key = value.toLowerCase(Locale.ROOT);
        return key.contains(":") ? key : "minecraft:" + key;
    }

    private void material(String material, String path) {
        if (material == null || !(environment.materials().contains(material) || environment.materials().contains(material.toUpperCase(Locale.ROOT)) || environment.materials().contains(registryId(material)))) fail(path, "UNKNOWN_MATERIAL", "服务端物品注册表中找不到此名称；例如 iron_ingot 或 minecraft:iron_ingot。其他插件的私有物品名称不一定已注册，不会生成替代物品");
    }
    private void text(TextSpec value, String path, boolean required) {
        if (value == null) { if (required) fail(path, "REQUIRED", "文字必填"); return; }
        try { texts.render(value); } catch (RuntimeException failure) { fail(path, "INVALID_TEXT", "TextSpec 内容不合法"); }
    }
    private void texts(List<TextSpec> values, String path) { var list = orEmpty(values); for (int i = 0; i < list.size(); i++) text(list.get(i), path + "/" + i, true); }
    private static float number(Double value, String path) {
        if (value == null || !Double.isFinite(value) || !Float.isFinite(value.floatValue())) fail(path, "INVALID_NUMBER", "需要可转换为有限 float 的数值");
        return value.floatValue();
    }
    private static double finite(Double value, String path, double fallback) {
        if (value == null) return fallback;
        if (!Double.isFinite(value)) fail(path, "INVALID_NUMBER", "需要有限数值");
        return value;
    }
    private static void range(Integer value, int min, int max, String path) { if (value != null && (value < min || value > max)) fail(path, "OUT_OF_RANGE", "请填写 " + min + " 到 " + max + " 之间的整数；当前填写的是 " + value); }
    private static void key(String key, String path) { if (key == null || !key.matches("[A-Za-z0-9_.-]{1,128}")) fail(path, "INVALID_KEY", "需要合法且非空的键"); }
    private static void unrelated(boolean present, String path) { if (present) fail(path, "UNRELATED_FIELD", "当前 Dialog 类型不使用该配置"); }
    private static void timestamp(String value, String path) {
        try { java.time.OffsetDateTime.parse(value); } catch (RuntimeException failure) { fail(path, "INVALID_TIMESTAMP", "时间必须为带时区的 ISO-8601 格式"); }
    }
    private static void checkTree(JsonNode node, String path, int depth) {
        if (depth > 64) fail(path, "MAX_DEPTH", "文档嵌套深度超过 64");
        if (node.isNumber() && !Double.isFinite(node.doubleValue())) fail(path, "INVALID_NUMBER", "数值必须为有限 double 范围");
        if (node.isObject()) node.properties().forEach(entry -> checkTree(entry.getValue(), path + "/" + escape(entry.getKey()), depth + 1));
        else if (node.isArray()) for (int i = 0; i < node.size(); i++) checkTree(node.get(i), path + "/" + i, depth + 1);
    }
    private static void fail(String path, String code, String error) { throw ApiException.field(path, code, error); }
    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    private static <T> List<T> orEmpty(List<T> list) { return list == null ? List.of() : list; }
}
