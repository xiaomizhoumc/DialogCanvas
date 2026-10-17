# Paper Dialog API 常用签名速查

2026-10-16 阶段 8.5：固定 Build 74 API 的 javap 确认 `Server.getCommandMap()`、`Server.getPlayerExact(String)`、`getOnlinePlayers()`、`Commands.getDispatcher()` 和 `Commands.register(String,String,Collection<String>,BasicCommand)`。JavaPlugin.registerCommand 字节码本身委托 LifecycleEvents.COMMANDS；本轮仍在此生命周期注册同一 BasicCommand，只在实际注册前增加 Brigadier 根节点/CommandMap 的 dme 冲突检查。普通补全与执行的线程依据及权限见 MANAGEMENT_COMMANDS.md。

2026-10-14 命令 API 核对：固定 `paper-api-26.1.2.build.74-stable.jar` 通过 javap 确认 `BasicCommand.execute(CommandSourceStack,String[])`、`suggest(CommandSourceStack,String[]):Collection<String>`、`permission():String` 和 `JavaPlugin.registerCommand(String,String,Collection<String>,BasicCommand)`。命令采用现有注册方式，执行切主线程，补全只读取正式注册表 ID；默认 canUse 按 permission 交由 Paper 分发器控制。

2026-10-14 同排正文核对：现有 `DialogBody.item(stack).description(PlainMessageDialogBody)` 由 DialogRenderer 使用 BodyBlock.Item.description 构造，复用原生物品＋文字布局。固定 Build 74 `ItemDialogBodyImpl.BuilderImpl` 字节码确认物品区域缺省 width=16、height=16；配置范围仍为 1–256。description 为文本正文，有自己的文字宽度，不与物品名称/lore 混用。

2026-10-14 预览布局核对：固定 Build 74 的 `MultiActionTypeImpl.BuilderImpl` 字节码确认缺省 `columns=2`；`ActionButtonImpl.BuilderImpl` 确认缺省 `width=150`；NumberRange、SingleOption、Text 输入 BuilderImpl 确认缺省 `width=200`。本项目这三种菜单的 DialogRenderer 只使用各按钮的 width，未将菜单 buttonWidth 传给 builder。网页以这些实际行为为准，不写回缺省值；浏览器仍不能等同客户端像素布局。

> 项目：DialogCanvas（DCMenu）。目标版本与核对基准：`paper-api 26.1.2.build.74-stable`，查阅日期 2026-10-08。Gradle 依赖和测试服务器应对齐此构建；若以后升级，先对照目标版本的官方 Javadoc，再以编译结果为准。

下列代码块采用“返回类型 + 所属类型.方法名”的速查写法，保留关键可空标记，省略 `@Contract` 等非调用必需注解；它们不是可直接粘贴进 Java 类的声明。

## 1. 创建、打开、关闭

包名：`io.papermc.paper.dialog`、`io.papermc.paper.registry`、`io.papermc.paper.registry.data.dialog`。`showDialog` / `closeDialog` 来自 Adventure 的 `Audience`，`Player` 可以调用。

```java
static Dialog Dialog.create(
    Consumer<RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder>> value
);

B RegistryBuilderFactory<T, B>.empty();
B RegistryBuilderFactory<T, B>.copyFrom(TypedKey<T> key);

DialogRegistryEntry.Builder DialogRegistryEntry.Builder.base(DialogBase dialogBase);
DialogRegistryEntry.Builder DialogRegistryEntry.Builder.type(DialogType dialogType);

default void Audience.showDialog(DialogLike dialog);
default void Audience.closeDialog();
```

- `Dialog.create(...)` 可在服务器正常运行期间动态创建。`empty()` 和 `copyFrom(...)` 对同一个 factory 实例合计只能调用一次。
- 创建条目时 `base(...)` 与 `type(...)` 都是必填。
- `Dialog` 可不带 registry key；不要依赖 `Dialog.getKey()` / `Dialog.key()`，两者已标记为待移除。

来源：[Dialog](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/dialog/Dialog.html)、[RegistryBuilderFactory](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/RegistryBuilderFactory.html)、[DialogRegistryEntry.Builder](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/DialogRegistryEntry.Builder.html)、[Audience](https://jd.advntr.dev/api/latest/net/kyori/adventure/audience/Audience.html)、[Paper Dialog 指南](https://docs.papermc.io/paper/dev/dialogs/)。

## 2. 标题、正文、输入

包名：`io.papermc.paper.registry.data.dialog`、`.body`、`.input`。

```java
static DialogBase.Builder DialogBase.builder(Component title);
DialogBase.Builder DialogBase.Builder.externalTitle(@Nullable Component externalTitle);
DialogBase.Builder DialogBase.Builder.canCloseWithEscape(boolean canCloseWithEscape);
DialogBase.Builder DialogBase.Builder.pause(boolean pause);
DialogBase.Builder DialogBase.Builder.afterAction(DialogBase.DialogAfterAction afterAction);
DialogBase.Builder DialogBase.Builder.body(List<? extends DialogBody> body);
DialogBase.Builder DialogBase.Builder.inputs(List<? extends DialogInput> inputs);
DialogBase DialogBase.Builder.build();

static PlainMessageDialogBody DialogBody.plainMessage(Component contents);
static PlainMessageDialogBody DialogBody.plainMessage(Component contents, int width);
static ItemDialogBody.Builder DialogBody.item(ItemStack item);

static BooleanDialogInput.Builder DialogInput.bool(String key, Component label);
static TextDialogInput.Builder DialogInput.text(String key, Component label);
static NumberRangeDialogInput.Builder DialogInput.numberRange(
    String key, Component label, float start, float end
);
static SingleOptionDialogInput.Builder DialogInput.singleOption(
    String key, Component label, List<SingleOptionDialogInput.OptionEntry> entries
);
static SingleOptionDialogInput.OptionEntry SingleOptionDialogInput.OptionEntry.create(
    String id, @Nullable Component display, boolean initial
);
```

- `DialogBase.DialogAfterAction` 值：`CLOSE`、`NONE`、`WAIT_FOR_RESPONSE`。`pause` 只对单人游戏暂停有意义。
- Build 74 的 DialogBase Builder 默认 `pause=true`、`afterAction=CLOSE`；构造器拒绝 `pause=true + NONE`。2026-10-13 官方 Javadoc 访问服务返回 503，本次约束直接核对工作区固定 Build 74 的 DialogBaseImpl / BuilderImpl 字节码。DCMenu 对旧网页 `NONE + pause 缺省/null` 显式使用 `pause=false` 并提供 LEGACY_PAUSE_DEFAULT 警告，不修改存储文档；显式 `true + NONE` 在保存前拒绝。
- 输入 `key` 用于响应取值和命令模板变量。单选条目最多一个 `initial=true`。
- 常用 Builder 方法：布尔输入 `initial/onTrue/onFalse/build`；文本输入 `width/labelVisible/initial/maxLength/multiline/build`；数值范围 `width/labelFormat/initial/step/build`；单选 `width/labelVisible/build`。
- 多行文本配置：`TextDialogInput.MultilineOptions.create(@Nullable Integer maxLines, @Nullable Integer height)`；两个数值分别要求正数、1–512。正文和输入宽度等具体范围应以对应方法的 Javadoc 注解为准。

来源：[DialogBase.Builder](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/DialogBase.Builder.html)、[DialogAfterAction](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/DialogBase.DialogAfterAction.html)、[DialogBody](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/body/DialogBody.html)、[DialogInput](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/input/DialogInput.html)、[单选条目](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/input/SingleOptionDialogInput.OptionEntry.html)、[多行文本配置](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/input/TextDialogInput.MultilineOptions.html)。

## 3. 按钮与 Dialog 类型

包名：`io.papermc.paper.registry.data.dialog`、`.type`。

```java
static ActionButton.Builder ActionButton.builder(Component label);
static ActionButton ActionButton.create(
    Component label, @Nullable Component tooltip, int width, @Nullable DialogAction action
);
ActionButton.Builder ActionButton.Builder.tooltip(@Nullable Component tooltip);
ActionButton.Builder ActionButton.Builder.width(int width);
ActionButton.Builder ActionButton.Builder.action(@Nullable DialogAction action);
ActionButton ActionButton.Builder.build();

static NoticeType DialogType.notice();
static NoticeType DialogType.notice(ActionButton action);
static ConfirmationType DialogType.confirmation(ActionButton yesButton, ActionButton noButton);
static MultiActionType.Builder DialogType.multiAction(List<ActionButton> actions);
static MultiActionType DialogType.multiAction(
    List<ActionButton> actions, @Nullable ActionButton exitAction, int columns
);
static DialogListType.Builder DialogType.dialogList(RegistrySet<Dialog> dialogs);
```

- `MultiActionType.Builder`：`exitAction(@Nullable ActionButton)`、`columns(int)`、`build()`。
- `DialogListType.Builder`：`exitAction(@Nullable ActionButton)`、`columns(int)`、`buttonWidth(int)`、`build()`。`dialogList` 的参数是 `RegistrySet<Dialog>`，**不是** `List<Dialog>`；不要凭记忆把两者写混。
- 按钮宽度范围是 1–1024；`columns` 要求正数。`action` 可为 `null`。

来源：[ActionButton](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/ActionButton.html)、[ActionButton.Builder](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/ActionButton.Builder.html)、[DialogType](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/type/DialogType.html)、[MultiActionType.Builder](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/type/MultiActionType.Builder.html)、[DialogListType.Builder](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/type/DialogListType.Builder.html)。

## 4. 点击动作、回调、读取响应

包名：`io.papermc.paper.registry.data.dialog.action`、`io.papermc.paper.dialog`、`io.papermc.paper.event.player`。

```java
static DialogAction.CustomClickAction DialogAction.customClick(
    DialogActionCallback callback, ClickCallback.Options options
);
static DialogAction.CustomClickAction DialogAction.customClick(
    Key id, @Nullable BinaryTagHolder additions
);
static DialogAction.CommandTemplateAction DialogAction.commandTemplate(String template);
static DialogAction.StaticAction DialogAction.staticAction(ClickEvent value);

void DialogActionCallback.accept(DialogResponseView response, Audience audience);
@Nullable String DialogResponseView.getText(String key);
@Nullable Boolean DialogResponseView.getBoolean(String key);
@Nullable Float DialogResponseView.getFloat(String key);
BinaryTagHolder DialogResponseView.payload();

Key PlayerCustomClickEvent.getIdentifier();
@Nullable BinaryTagHolder PlayerCustomClickEvent.getTag();
@Nullable DialogResponseView PlayerCustomClickEvent.getDialogResponseView();
PlayerCommonConnection PlayerCustomClickEvent.getCommonConnection();
```

- 两条点击路线：使用 `customClick(callback, options)` 直接回调，或使用 `customClick(id, additions)` 并监听 `PlayerCustomClickEvent`。
- `DialogResponseView` 文档明确说响应不保证有效，插件必须自己校验；`getText/getBoolean/getFloat` 可能返回 `null`。事件的 `getDialogResponseView()` 也可能是 `null`。
- 命令模板中的 `$(variable_name)` 对应 `DialogInput.key()`。若动作涉及权限、花费或物品发放，仍须在服务端处理时重新校验，不能把客户端输入当成可信数据。
- `ClickCallback.Options` 是 Adventure 类型，可通过 `Options.builder()` 设置 `uses(int)`、`lifetime(TemporalAmount)`；`Duration` 可作为 `TemporalAmount` 参数传入。默认单次使用。此项应再核对项目实际绑定的 Adventure 版本。

来源：[DialogAction](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/action/DialogAction.html)、[DialogActionCallback](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/action/DialogActionCallback.html)、[DialogResponseView](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/dialog/DialogResponseView.html)、[PlayerCustomClickEvent](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/event/player/PlayerCustomClickEvent.html)、[Adventure ClickCallback.Options](https://jd.advntr.dev/api/latest/net/kyori/adventure/text/event/ClickCallback.Options.html)、[Adventure Options.Builder](https://jd.advntr.dev/api/latest/net/kyori/adventure/text/event/ClickCallback.Options.Builder.html)。

## 5. 注册式 Dialog 与项目选择

Paper 官方指南说明：注册到 Dialog registry 的定义要在插件 bootstrap 阶段通过 `RegistryEvents.DIALOG` 写入；运行期间可直接用 `Dialog.create(...)` 创建。注册式定义适合需要 registry key 或被命令参数引用的场景。[Paper Dialog 指南](https://docs.papermc.io/paper/dev/dialogs/)

**DialogCanvas 的设计建议（推论，不是 API 保证）**：网页保存后希望新菜单立即生效，优先从文件定义动态构造 Dialog，并在打开菜单时使用最新定义。已经在玩家屏幕上打开的旧 Dialog 是否自动更新，不能从 `Dialog.create` 的签名推出；若产品要求立即刷新，需要显式向玩家重新展示新 Dialog。若以后需要 `/dialog show` 等基于 registry key 的引用，再单独设计 bootstrap 注册方案。

## 6. 原生 Sprite 按钮标签（2026-10-11）

2026-10-14 扩展目录：调用签名不变。映射依据校验过的 Mojang 26.1.2 客户端模型和 atlases/items.json、atlases/blocks.json 目录源，支持 1423 种基础纹理；不按 Material 名猜路径。模型继承与 `#` 纹理引用解析后，只有存在 PNG 且图集包含该 Sprite 才收录。特殊实体模型不提供映射；多层、染色、立体和动画仅作基础纹理展示。前后端生成目录与离线验证脚本见 FRONTEND.md。

目标 Paper API 仍为 `26.1.2.build.74-stable`，本项目实际依赖的 Adventure API/key 为 `4.26.1`。以下签名已核对同版官方 Javadoc 和本地 JAR javap，并通过目标依赖编译：

```java
static Key Key.key(String string);
static SpriteObjectContents ObjectContents.sprite(Key atlas, Key sprite);
static ObjectComponent Component.object(ObjectContents objectContents);
static TextComponent Component.empty();
static TextComponent Component.space();
default Component Component.append(Component component);
default Component Component.color(@Nullable TextColor color);
ObjectContents ObjectComponent.contents();
Key SpriteObjectContents.atlas();
Key SpriteObjectContents.sprite();
static ActionButton.Builder ActionButton.builder(Component label);
```

SpriteObjectContents 的两个参数明确是 atlas、sprite，不是物品 Material。只传一个 Key 的 sprite 重载使用默认图集，本项目使用两参数重载避免物品/方块图集混淆。序列化器可能省略默认 minecraft:blocks 图集，反序列化会恢复默认值，不应以 JSON 字段是否显式出现判断映射失败。

按钮组成 `Component.empty().append(icon).append(Component.space()).append(text)`；图标设为白色，文字使用已有 TextRenderer。ActionButton 自身仍负责 tooltip、宽度和点击回调，图标不是另一个点击目标。

来源：[ObjectContents 4.26.1](https://jd.advntr.dev/api/4.26.1/net/kyori/adventure/text/object/ObjectContents.html)、[SpriteObjectContents 4.26.1](https://jd.advntr.dev/api/4.26.1/net/kyori/adventure/text/object/SpriteObjectContents.html)、[Component 4.26.1](https://jd.advntr.dev/api/4.26.1/net/kyori/adventure/text/Component.html)、[ObjectComponent 4.26.1](https://jd.advntr.dev/api/4.26.1/net/kyori/adventure/text/ObjectComponent.html)、[Key 4.26.1](https://jd.advntr.dev/key/4.26.1/net/kyori/adventure/key/Key.html)、[ActionButton Build 74](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/ActionButton.html)。

组件签名验证不证明客户端图集实际命中或像素布局；具体配置、映射表与真机检查清单见 TEXT.md。

## 7. 快捷主菜单事件签名（2026-10-14）

本次使用固定 `26.1.2.build.74-stable` 本地 Paper API JAR 的 javap、实际编译和 MockBukkit 测试核对：

```java
public PlayerSwapHandItemsEvent(Player player, ItemStack mainHandItem, ItemStack offHandItem);
public boolean PlayerSwapHandItemsEvent.isCancelled();
public void PlayerSwapHandItemsEvent.setCancelled(boolean cancel);
public Player PlayerEvent.getPlayer();
boolean Player.isSneaking();
boolean CommandSender.hasPermission(String permission);
default boolean BasicCommand.canUse(CommandSender sender);
```

监听原生同步 PlayerSwapHandItemsEvent，HIGHEST、ignoreCancelled=true，在 Scheduler.sync 内检查在线、潜行、权限和已提交主菜单，再取消物品交换并沿用 SavedMenuOpener。没有可用主菜单时不取消；同玩家打开处理中忽略后续触发，完成或失败后释放占用。

官方 Javadoc 地址为 https://jd.papermc.io/paper/26.1.2/org/bukkit/event/player/PlayerSwapHandItemsEvent.html 和对应 BasicCommand 页面。本轮访问返回 HTTP 503，不能声称本轮已在线核对；实际签名依据上述固定版本 JAR。客户端真实快捷键、其他插件交互和显示效果仍需真机确认。

## 使用这份清单的规则

1. Gradle 依赖固定为 `compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")`；测试服务器也使用 Paper 26.1.2 Build 74。
2. 写到一个此前未列出的重载、Builder 方法或事件时，打开**同一版本**官方 Javadoc 核对参数、返回值、可空性与注解。
3. 每次升级 Paper 后重新检查本文件，再做一次实际编译；尤其留意 `io.papermc.paper.registry.data.dialog` 包的 `@Experimental` 标记。
