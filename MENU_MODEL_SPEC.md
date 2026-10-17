# DialogCanvas 菜单模型规范草案

状态：API 字段与校验方案已整理；购买数量、限购计数单位及购买失败处理已由用户确认。文中其余待确定项不作为实现默认值。

基准：Paper API `26.1.2.build.74-stable`。本文描述模型和校验契约，尚不代表已有 Dialog 构造器或购买执行器。

## 1. 持久化与版本

- 网页 API 使用 JSON；菜单和模板使用 YAML；玩家数据与结构化历史快照使用 JSON。
- 同一份菜单逻辑树由 `JsonNode` 表达，转换格式不改变业务字段含义。
- `schemaVersion` 表示模型格式版本；首版计划为 `1`。
- `metadata.version` 表示菜单修订号；创建和更新时间使用带时区的 ISO 8601 时间。
- 菜单 `id` 在菜单集合内唯一；按钮 `id` 在同一菜单内唯一；输入 `key` 在同一菜单内唯一。
- YAML 中的颜色代码、表达式、数字形式字符串须按字符串保存。JSON 输入须为有效 JSON，不能保留 HTML 实体和行末续行反斜杠。

## 2. 统一文本表示

保留字符串简写供原示例兼容，简写按项目的 `&` 颜色代码格式解析。

复杂文本采用统一 TextSpec 对象，字段为 `format` 和 `value`：

| format | value | 用途 |
|---|---|---|
| `plain` | 字符串 | 不解析颜色代码或标签的普通文本 |
| `legacy` | 字符串 | 明确解析 `&` 格式 |
| `minimessage` | 字符串 | 渐变和丰富样式 |
| `component` | Adventure Component JSON 树 | 保留翻译、分段样式等原生组件能力 |

该约定适用于标题、外部标题、正文、输入标签、按钮标签、按钮提示、单选显示名和物品名称/lore。

旧示例的 `colorMode/color` 在正式确定兼容转换规则后迁移进 TextSpec；不让单色、渐变和嵌入的颜色代码形成多套冲突的渲染规则。TipTap 的编辑数据须转换成 TextSpec，不能直接作为游戏文本输出。

## 3. 公共 Dialog 字段

| 字段 | 契约 |
|---|---|
| `id` | 菜单标识，不等同于 Paper registry key |
| `schemaVersion` | 模型格式版本，未知版本应拒绝并给出错误路径 |
| `title` | 必填 TextSpec 或兼容字符串 |
| `externalTitle` | 可缺省或为 null，存在时使用统一文本格式 |
| `dialogType` | `notice/confirmation/multi_action/dialog_list/server_links` |
| `canCloseWithEscape` | 布尔值 |
| `afterAction` | `CLOSE/NONE/WAIT_FOR_RESPONSE` |
| `pause` | 布尔值，只对应单人游戏暂停能力 |
| `body` | 有序正文列表 |
| `inputs` | 有序输入列表 |
| `metadata` | 菜单修订号、时间和作者信息 |

## 4. 类型专属字段

| dialogType | 对应字段 |
|---|---|
| `notice` | `noticeAction`，可省略以采用原生默认按钮 |
| `confirmation` | 明确的 `yesButton` 和 `noButton` |
| `multi_action` | `actions` 按钮列表、可空的 `exitAction`、正整数 `columns` |
| `dialog_list` | `dialogs` 引用列表、可空的 `exitAction`、正整数 `columns`、`buttonWidth` |
| `server_links` | 可空的 `exitAction`、正整数 `columns`、`buttonWidth`；链接来自服务器链接配置 |

`buttonWidth` 和按钮 `width` 为 1–1024。普通取消按钮与 `exitAction` 是两个独立布局位置。

`dialogs` 引用须区分项目菜单 ID 和原生 registry key；运行时解析为 `RegistrySet<Dialog>`。引用不存在、形成循环或不支持的引用方式必须报错。注册式 Dialog 的 bootstrap 流程另行实现，不能把热重载文件 ID 自动视为注册键。

## 5. 正文

- `type=text`：统一文本字段 `text` 和 `width`，转换为 PlainMessageDialogBody。
- `type=item`：物品定义以及 `description/showDecorations/showTooltip/width/height`。描述是独立的文本正文，不等同于物品 lore。
- 物品正文宽高为 1–256；描述自身使用文本正文格式。
- 原示例的 `material/name/lore/amount/glow` 是便捷物品定义，只覆盖 ItemStack 的一部分。完整物品元数据/数据组件的编码与还原方案须在实现物品转换器前确定。
- 展示物品和实际发放物品必须显式关联，不能假定材质相同就自动继承名称、lore 或发光效果。

## 6. 输入

| type | 字段 |
|---|---|
| `bool` | `key/label/initial/onTrue/onFalse` |
| `text` | `key/label/initial/width/maxLength/labelVisible/multiline` |
| `number_range` | `key/label/start/end/step/initial/width/labelFormat` |
| `single_option` | `key/label/width/labelVisible/options` |

- `multiline` 可空，非空时包含可空的 `maxLines/height`；设置时 maxLines 为正数，height 为 1–512。
- `maxLength` 为正整数；文本、数值和单选输入的 width 为 1–1024。
- `number_range` 的 initial 和 step 可省略或为 null；设置的 step 必须为正且有限。范围端点必须有限，initial 设置时须在范围内。
- 单选项包含 `id/display/initial`；display 可空；选项 ID 不重复，最多一个 initial 为 true。
- UI 初始值与客户端最终响应分开处理。服务端重新校验类型、缺失值、范围、整数要求和单选成员资格。

## 7. 按钮点击与业务行为

原版 Sprite 第一阶段新增可选 `icon: {"type":"item","material":"BONE"}`，与 label 分开配置。缺省图标保持旧菜单；类型默认 item，仅支持 TEXT.md 中的五种显式映射。图标解析失败记录 WARN，保留文字、tooltip 和按钮回调。label 原有 legacy/plain/minimessage/component 格式保持不变；原生 Sprite 组件与空格在文字左侧组合。

保留现有按钮的 `id/label/tooltip/width/actionType`。tooltip 可空。原生点击能力需支持：

- `custom`：actionId、可选附加数据，运行时绑定事件或注册的回调。
- 命令模板：明确的 template，输入引用遵循 `$(key)`。
- 静态点击：可序列化的 ClickEvent 类型及其参数，以目标 API 支持范围为准。
- 无点击动作：显式允许空动作。
- `close`：编辑器业务语义，转换器必须明确其与 afterAction 的组合，不能作为未经转换的原生 DialogAction 类型。

回调函数本身不写入配置。若使用回调路线，运行时配置使用次数和有效期。自定义点击提交须关联玩家、打开会话、菜单修订号和按钮 ID；配置中的 actionId 本身不能证明提交有效。

按钮业务规则包括 conditions、动作链、denyActions、限购、冷却和刷新。它们由插件逻辑引擎执行，不属于原生 Dialog 的布局字段。

条件树须区分分组节点和叶节点，避免分组 AND/OR 与叶节点比较 operator 混淆；明确每种节点的 negate 语义。原示例只定义了 permission、placeholder、player_data 三种叶节点；其余条件类型需要单独明确。输入条件需能够读取已校验的 key。

步骤 3.1 完成设计确认，步骤 3.2 已实现五种叶节点：permission、placeholder、player_data、numeric、string。ConditionDefinition 增加 comparison/left/right，ValueSourceDefinition 描述 literal/input/placeholder/player_data 来源。确认框等输入条件通过 string/numeric 的 input 来源表达，不增加第六种 input 条件。数值依用户最新要求采用 double，详细契约见 CONDITION_ENGINE.md。

引用采用受限的变量替换和表达式语法；客户端输入作为值使用，不能拼接成新的表达式或不受限的命令。

delay、cooldown 的单位、chance 的范围与缺省值，以及 refreshAfterAction 的触发条件，须在动作执行器实现前锁定。限购管理器统一管理计数和周期重置，避免手写 player_data 条件与内置 limit 并行维护同一额度。

## 8. 武器商店已确认规则

1. 按已校验的 amount 数量购买，单价 1000，总价为 1000 × amount；confirm 必须为 true。amount 必须为合法整数，不能直接信任客户端传值。note 的业务用途仍需说明。
2. 每位玩家每天最多购买 3 把剑，按物品数量累计，由一个限购管理器负责额度及跨日重置。购买数量不能超过当日剩余额度；不再通过 buy_limit.sword 的手动条件和增量动作维护第二份计数。
3. 购买流程先校验并串行执行。扣款失败立即中断；发货失败尝试退款；补偿失败记录待处理交易。成功消息、声音及成功购买计数不得在购买失败时执行。

原示例中的固定扣款 1000 和固定发放 1 把须改为引用已校验的 amount；余额检查须使用总价。滑块最大值 64 不表示允许超过每日额度，实际提交须再次检查剩余额度。

仍需在购买模块实现前确定：每日重置时区与边界、库存空间不足的处理、购买中的退出/停服处理、事务提交与计数更新顺序，以及旧会话在菜单热重载后的有效性。

afterAction=CLOSE 与 refreshAfterAction=true 的组合必须明确是否关闭后重开、是否保留输入，以及失败/拒绝是否刷新。不能仅靠保存字段推断行为。

## 9. 校验与测试顺序

校验分为文档结构校验、服务端引用与环境校验、点击时响应与业务校验三个阶段。结构合法不能证明引用存在、回调有效或购买成功。

第一轮实现文档结构校验，返回字段路径和错误说明，不执行 Bukkit 游戏操作。覆盖：

| 测试 | 预期 |
|---|---|
| 五种 Dialog 类型的最小合法文档 | 通过结构校验 |
| confirmation 缺少 yes/no 按钮 | 指出对应字段缺失 |
| 未知类型或 schemaVersion | 拒绝 |
| 输入 key 或按钮 id 重复 | 指出重复节点 |
| 非整数宽度/columns、超界尺寸 | 拒绝 |
| 非有限数值、非正 step、越界 initial | 拒绝 |
| 多个单选 initial 或重复选项 ID | 拒绝 |
| 不合法的 TextSpec | 指出文本路径 |

引用、注册及玩家行为使用 MockBukkit 验证；购买模块实现后至少覆盖以下业务用例：

- confirm 为 false 或缺失时拒绝购买，不扣款、不发货。
- 购买 2 把时扣款 2000、发放 2 把、消耗 2 把额度。
- 已购买 2 把后提交购买 2 把，因超过每日 3 把额度而拒绝。
- 扣款失败时不发货、不执行成功分支。
- 发货失败时尝试退款；退款失败时持久化待处理交易。
- 重复点击、跨日重置及失败补偿按后续确定的会话与交易契约验证。

每个功能测试通过后再进入下一个功能，不使用无头 Bot。

## 10. 核对来源

- [DialogType](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/type/DialogType.html)
- [DialogBase](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/DialogBase.html)
- [ItemDialogBody.Builder](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/body/ItemDialogBody.Builder.html)
- [DialogInput](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/input/DialogInput.html)
- [TextDialogInput.MultilineOptions](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/input/TextDialogInput.MultilineOptions.html)
- [DialogAction](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/registry/data/dialog/action/DialogAction.html)
- [DialogResponseView](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/dialog/DialogResponseView.html)
- [Adventure 文本组件](https://docs.papermc.io/adventure/text/)

## 11. 当前 POJO 实现

模型位于 `plugin/src/main/java/dialogcanvas/model`，字段使用 camelCase，提供无参构造与 getter/setter，不依赖 Bukkit。

- `MenuDefinition` 表示菜单文档，`DialogType/AfterAction` 表示已确定的枚举。
- `BodyBlock`、`InputDefinition`、`ActionStepDefinition` 使用显式 `type` 多态，各具体类型为对应类的公开静态内部类；不启用 Jackson 全局默认类型推断。
- `ActionDefinition` 表示按钮；按钮的 `actions/denyActions` 元素是 `ActionStepDefinition`，避免布局按钮与业务动作混为一类。
- `ConditionDefinition` 保留原示例无 type 的分组结构。分组/叶节点的互斥字段、已支持条件类型及比较规则由后续校验器检查。
- `TextSpec` 支持字符串简写和 format/value 对象；字符串往返保持简写形式。旧正文 colorMode/color 暂时仅保留数据。
- `MenuMetadata`、`MultilineDefinition`、`OptionDefinition`、`LimitDefinition`、`DialogReference`、`StaticClickDefinition`、`CallbackOptions` 承载辅助配置。
- `DialogReference` 采用 menuId/registryKey 二选一；`additions` 为 SNBT 字符串；静态点击对象使用 action/value。CallbackOptions 的 lifetime 目前仅保存字符串，解释规则仍待确定。
- 数字、布尔和列表字段缺省时保留 null，不在 POJO 内推断未确认的运行时默认值。数值序列化可能将 0 规范为 0.0；物品 description 接受不带 type 的文本正文，输出时带 type=text。
- JSON 入口应使用 `MenuJson.createMapper()`，显式拒绝未知字段和重复字段。默认 Jackson 3 mapper 可以读取模型，但不保证未知字段被拒绝。

反序列化成功不代表满足所有业务规则。必填字段、范围、重复 ID、引用存在性、条件节点组合、输入提交与购买事务仍由后续校验/运行模块负责。当前只覆盖已确认的便捷物品字段，完整 ItemStack 编码尚未实现。

纯数据模型使用 JUnit 测试，不需启动模拟服务器；涉及 Bukkit 的转换与玩家行为继续使用 MockBukkit。
