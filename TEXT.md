# 原版物品图标＋右侧文字按钮

> 当前文字/颜色/渐变操作见 [Wiki 教程](docs/wiki/02-editor/text.md)，完整 TextSpec 契约见 [字段参考](docs/wiki/06-reference/menu-fields.md)。本文件保留分阶段技术记录，旧界面描述不作为当前操作步骤。

## 成交消息与变量（2026-10-15）

- 消息动作现在解析本次点击的数值输入：`$(sell_slider)`、`$(buy_text)` 等，不再将标记直接发到聊天框。
- 金额计算复用经济动作的受限数值表达式，例如 `$(1 * $(sell_slider))`；外层表示在消息中输出计算结果，内部引用点击时已校验的数值输入。仅支持数值输入，不执行脚本，不将文本输入当算式。小数不显示无意义的尾零。
- 示例：`&6出售橡木原木 ×$(sell_slider)，获得 $(1 * $(sell_slider)) 游戏币。`；购买：`&a花费 $(1 * $(buy_text)) 游戏币，购买橡木原木 ×$(buy_text)。`
- 数值表达式在事务执行前检查；交易提交后才发送成功消息。普通文本、颜色、渐变仍使用原 TextSpec；Component 仅替换 text/extra/with 文本，不替换点击命令等元数据。
- 消息支持既有 PAPI 变量，例如 `%player_name%`、`%vault_eco_balance%`，通过现有可选 PlaceholderResolver 在主线程、状态提交后解析。相关插件及 expansion 必须可用；未替换的变量给出 WARN，消息失败明确记为 POST_COMMIT_FAILED，不重复扣款或声称已回滚。无需注册新的 PAPI expansion。
- 商店的 JSON、YAML 和同步基线已重新生成；数量及总金额使用相同点击快照和相同价格表达式。旧菜单“每个单价”的文字不会由 JAR 自动改为总金额，须更新对应消息模板。更新正式 JSON/配套 YAML/基线请先停服并备份；网页修改则按正常保存流程更新。
- 已运行自动测试；本次未部署原服务端，未连接 Minecraft 客户端或真实 Vault/PAPI 组合。

当前网页（2026-10-14）已移除高级源码和格式选择，直接通过可视化样式、颜色及渐变窗口编辑。菜单文件的 TextSpec 格式仍兼容；复杂文本保护为只读，原配置保持不变。普通发货不使用装饰性光效，旧 give_item.glow 配置不再让无附魔钻石剑闪光；正文展示光效独立设置，不代表真实附魔，旧背包物品需自行处理。

## 当前文字颜色与渐变契约（阶段 6.5，2026-10-14）

文字仍使用原有 TextSpec；字符串简写按后端 legacyAmpersand 解释，显式对象支持 plain、legacy、minimessage、component。网页只对已经验证的文本子集开放可视化，不自动改写未知标签、交互或复杂 Component。

```yaml
title:
  format: minimessage
  value: '前<gradient:#FF5555:#55FF55:#5555FF><bold>五彩标题</bold></gradient>后'
```

- 上例为原生三色、均匀分布渐变。范围内字符按 Unicode 码点计算，空格和换行参与计数；粗体、斜体等节点分割不重新开始插值。颜色值无透明度，窗口统一输出六位 Hex。
- 单色示例：`{format: minimessage, value: '<color:#55FFFF>浅蓝文字</color>'}`。legacy 示例：`'&a绿色&l粗体&r恢复'`；新 legacy 颜色会清除此前装饰。后端支持 `&#123456` 与完整 `&x&1&2&3&4&5&6`，网页按相同规则转换。
- 标题、正文、输入 label、按钮 label/tooltip 共用文字编辑器与预览。按钮 tooltip 有独立彩色浮层；网页为近似预览，默认字体、阴影和布局仍可能与游戏不同。
- 图标仍单独配置 `icon`，不被文字颜色、渐变或清除颜色修改。源码里手写的 Sprite/交互等复杂标签继续保留原文。
- 窗口应用才修改菜单，取消不保存；未修改 TextSpec 保留原格式和源码。保存只提交 TextSpec，不提交 HTML、CSS、TipTap JSON 或渐变内部标识。
- 手动改 YAML 后执行控制台 `dcmenu reload <菜单ID>`，再网页重新读取。未导入 YAML 修改会阻止网页覆盖；源码不合法时保留上一份可用菜单。规范化 YAML 导出仍不保留手写注释。

固定 Adventure 4.26.1 的真实 TextRenderer 解析与逐字符颜色验收见 `build-reports/TEXT_WORKFLOW_ACCEPTANCE.md`。下方为图标说明及历史记录。

2026-10-14 正式命令调整：下方早期 test/icons/shop/menu 记录仅为历史验收，当前运行命令已停用这些入口，不再自动生成示例或发送示例回调调试消息。当前菜单请通过网页保存，再使用 `/dcmenu open <菜单ID>` 打开；正式命令说明见 LOCAL_EDITOR.md。

## 游戏按钮图标关闭投影（2026-10-14）

独立 icon 配置生成的 Sprite 组件显式使用 `ShadowColor.none()`，关闭游戏文本渲染附加的投影。只设置图标子组件，旁边文字仍保留原样式；纹理图片自带的暗色像素不改变。label 源码内自行编写的 Sprite 保持原配置。真实客户端效果需重新打开菜单人工检查。

## 当前纹理目录与网页选择器（2026-10-14）

按钮已扩展为 1423 种原版平面纹理映射，覆盖固定 26.1.2 客户端 1506 种物品定义中的约 94%。原 5 种图标保持原映射；其他图标由真实物品模型、纹理 PNG 和图集目录解析，不按物品名拼路径。

网页的正文物品、按钮图标、背包上限物品和动作物品均直接填写注册名，例如 iron_ingot 或 minecraft:diamond_sword；兼容已有大写 Material。取消图片选择、搜索和分类，不嵌入 PNG。网页以文字占位，游戏纹理由客户端提供。按钮图标仍需已核实的 Sprite 映射，未知图标保留文字；物品展示与实际发放仍遵循服务端物品注册表，私有插件 ID 不保证可生成。

资源随单 JAR 打包，不使用 CDN。生成与核对脚本分别为 scripts/update-vanilla-icons.py、scripts/verify-vanilla-icons.py。以下保留早期五种图标实现与验收记录。

## 第一阶段范围（2026-10-11）

游戏 Dialog 按钮标签使用原生 Sprite ObjectComponent，按「图标＋一个空格＋中文名称」排列，整组标签属于同一个按钮。图标来自原版客户端图集；本阶段为二维纹理，不生成或发放物品。没有接入 Web 编辑器、购买流程或自定义资源包图标。

当前上下文没有此前截图，本轮依据用户明确描述实现布局，没有宣称像素级复刻截图。

## 配置

新增可选 icon，label 继续使用既有 TextSpec。示例：

```json
{
  "id": "icon_bone",
  "type": "button",
  "icon": {"type": "item", "material": "BONE"},
  "label": "&f骨头",
  "tooltip": "&7骨头图标＋右侧文字",
  "width": 160,
  "actionType": "custom"
}
```

- icon 缺省时保留原按钮标签，不增加空格、不记录警告。
- icon.type 默认 item，material 支持枚举名或 minecraft: 命名空间键，不区分大小写。
- label 支持 legacy 字符串、plain、minimessage、component；tooltip 和原有按钮回调保持既有处理方式。
- 图标组件显式设为白色，与文字样式独立，避免文字的颜色配置传给图标；客户端实际颜色表现仍需检查。
- 已有 MiniMessage `<sprite:...>` 标签继续正常解析。不在本阶段自动转换 KaMenu 的 `&item:[...]` 语法；legacy 格式保持原有语义。
- 无效类型、缺少材质、非原版命名空间、未映射材质记录含菜单/按钮 ID 和原因的 WARN，省略图标并保留原文字及回调。故意无效的演示按钮每次构造菜单时产生一次 WARN。
- 文档不合法 JSON、未知字段或字段类型错误仍按既有严格 MenuJson 规则拒绝加载，不把损坏文档当成合法菜单。

## 显式图标映射

统一解析模块为 VanillaIconResolver。当前仅支持下列五种已明确映射的材质，其他材质即使是有效原版物品也不猜测路径：

| material | atlas | sprite |
| --- | --- | --- |
| BONE | minecraft:items | minecraft:item/bone |
| ENDER_PEARL | minecraft:items | minecraft:item/ender_pearl |
| BLAZE_ROD | minecraft:items | minecraft:item/blaze_rod |
| DIAMOND_SWORD | minecraft:items | minecraft:item/diamond_sword |
| STONE | minecraft:blocks | minecraft:block/stone |

石头使用方块图集中的平面纹理，与其他四种物品图集映射分别定义。本轮已校验输出键、组件结构和序列化往返，尚未在原版客户端验证实际 atlas 纹理显示。

扩展原版物品支持时，应先核对目标客户端图集/纹理，再补充映射与测试；不能默认所有 Material 都对应 item/<name>。

## 实现复用

- ButtonIconDefinition：与文字分开的可选图标配置。
- VanillaIconResolver：纯组件解析，显式映射，不访问 Bukkit、不读取资源包。
- TextRenderer.renderButtonLabel：复用现有文字解析，将图标、空格和文字组成独立子组件。
- DialogRenderer：把组合 label 传给原来的 ActionButton.builder，tooltip、宽度、owner 校验、ClickGuard 和主线程回调保持同一路径。
- TestMenuService：新增 ICONS 示例选择，仍采用异步读取和原子复制；首次生成后保留服主修改，每次命令重新读取。
- DialogEditorPlugin：新增 /dcmenu icons，演示点击只发送聊天确认并记录插件日志，不调用购买逻辑。

## 打开演示

1. 构建产物：plugin/build/libs/DialogCanvas-0.1.0.jar；先正常停服，再替换服务端同名插件 JAR 并重启，避免同时放置多个 DCMenu JAR。
2. 使用目标原版 26.1.2 客户端进入服务器；使用 OP 或 dcmenu.admin 权限。
3. 游戏内执行 `/dcmenu icons`。
4. 启动时首次生成 `plugins/DialogCanvas/examples/icon-buttons.json`（若服主改过插件数据目录，以实际插件目录为准），可修改后重新执行命令。
5. `/dcmenu test` 和既有 minimal-menu.json 保留。

演示为两列六个按钮：骨头、末影珍珠、烈焰棒、钻石剑、石头、故意无效图标；另有无图标关闭按钮。afterAction=NONE，物品按钮使用 64 次回调额度供重复测试，过期或额度耗尽后重新打开菜单。

## API 核对

Paper 保持固定依赖 `26.1.2.build.74-stable`，实际绑定 Adventure 4.26.1。新增签名已对照官方 Javadoc 和本地依赖 javap，再由目标依赖编译验证。

使用 `Component.object(ObjectContents.sprite(Key atlas, Key sprite))`；按钮仍是 `ActionButton.builder(Component label)`。官方来源与完整签名见 PAPER_DIALOG_API_REFERENCE.md 新增第 6 节。

## 验证与限制

完整 Gradle build 成功，181 项测试：178 项通过，3 项因 MockBukkit 缺少原生 Dialog builder provider 而跳过。本轮新增 21 项针对性测试通过，覆盖五种映射、原生组件/JSON 往返、别名、非法配置降级、文字样式独立、旧 MiniMessage Sprite、模型兼容、示例保留与主线程按钮回调。

本轮没有部署服务端、没有连接原版客户端，未验证实际像素布局、客户端图集命中、悬浮提示或按钮实际显示。请在原版客户端检查：

- 五种图标显示正确，不出现缺失纹理方块；石头显示预期的平面纹理。
- 图标左、空格居中、中文在右，两列不截断；带色文字没有把图标染色。
- tooltip 正常，六个按钮都产生对应 ID 的聊天回调；不扣款、不产生物品。
- 故意无效图标只保留文字，仍能点击，控制台出现可定位的 WARN。
- 关闭按钮与 Escape 正常；旧 /dcmenu test 仍正常。

对原版客户端资源是否存在，本服务端不能通过构建或 MockBukkit 证明；这些项目需玩家现场确认。

## 服务端测试包（2026-10-11）

新增两个 JSON，启动时生成到插件 examples 目录，已有文件不会被覆盖。每次执行命令重新读取 JSON，修改示例后无需重启。

| 游戏内命令（OP 或 dcmenu.admin） | JSON 文件 | 检查内容 |
| --- | --- | --- |
| `/dcmenu test` | minimal-menu.json | 原有 notice、关闭与 Escape |
| `/dcmenu menu` | menu-demo.json | 两列、传统颜色、MiniMessage 粗体、无图标与有图标按钮、tooltip、聊天回调 |
| `/dcmenu shop` | shop-demo.json | 钻石剑模型、附魔光效、lore、数量滑块、确认框、备注输入、按钮回调 |
| `/dcmenu icons` | icon-buttons.json | 五种原版 Sprite、中文标签、无效图标降级与 WARN |

商店按钮仅输出点击 ID 和测试提示，不检查确认框、不计算总价、不扣款、不发货、不计限购；输入控件本轮检查客户端显示及操作，不声称已经验证输入值的业务传递。经济等生产适配器与购买流程接入留在后续阶段。

每个示例可重复点击 64 次，默认回调过期或额度耗尽后重新执行命令。关闭菜单使用原有 close 行为。实际客户端效果等待服主现场测试。

本次完整 build 成功：182 项测试，179 项通过，3 项因 MockBukkit 缺少原生 Dialog provider 跳过，零失败。已在确认服务端停止后部署到 E:/project/server26.1.2/plugins/DialogCanvas-0.1.0.jar，并保留原 minimal-menu.json，新增其余三个示例。旧 JAR 备份目录为 E:/project/server26.1.2/backups/DCMenu/20261011-075317。

源文件与服务端 JAR 的 SHA256 一致：E6419CF04706F1EEB3946DA8745CFE2C8195FAEB7AFD86C2BAC2DFCE9435B1B8。本次未启动服务器或客户端，启动日志与实际显示尚待现场验证。
## 编辑器中文显示补充（2026-10-14）

菜单选项和文字格式名称使用中文，实际 TextSpec 格式值与源码保持不变。可视化工具提供粗体、斜体、下划线、颜色和渐变；复杂内容仍使用“高级源码”，不会自动翻译或丢失格式。

正文物品未填写自定义名称时，游戏内使用玩家客户端的原版翻译；网页使用固定 26.1.2 官方中文名称近似预览。图标和按钮文字仍独立配置，选择中文物品不会自动重写 label/name。逻辑面板的 `!` 提供用法和示例。

