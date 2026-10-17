# 06.2 菜单、TextSpec、正文与输入字段

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 会读 YAML；所有路径均相对于菜单根文档。

## 读表规则

类型严格校验，不自动把 `'1'` 转成整数。表中“可选”省略后 POJO 通常为 null；显式 null 对可选字段通常同样表示未配置，保存时 NON_NULL 规范化会省略，**不保证 null 字节往返**。网页未修改字段尽量保留原文，不等于正式保存不规范化。必填字段 null 仍报错；列表内部 null 元素报错；数组 `[]` 与省略/null 不总相同（例如另一类型的空 actions 仍是残留配置）。

“原生”表示渲染器未调用相应 setter，交由当前运行的 Paper 默认（编译基线 Build 74）；不臆测其数值。希望布局稳定就显式填写。正文/输入/按钮宽度不是任意屏幕坐标；所有数值必须有限，范围还受客户端原生限制。动态目录以当前 `/api/meta` 为准，反射 Schema 只覆盖结构，最终语义以 MenuValidator 为准。

## MenuDefinition 根字段

| 路径 | 类型/必填 | 缺省/null、范围、用途与示例 |
| --- | --- | --- |
| schemaVersion | 整数，必填 | 仅 1；null/省略错误。不是菜单 revision |
| id | 字符串，必填 | `[a-z0-9_-]{1,64}`；禁 validate、Windows con/prn/aux/nul/com1..9/lpt1..9；如 wiki_shop |
| title | TextSpec，必填 | 如 `'&6商店'`；null 不合法 |
| externalTitle | TextSpec，可选 | 原生外部标题；省略不设；如 `'选择菜单'` |
| dialogType | 字符串，必填 | notice / confirmation / multi_action；其余枚举即使 POJO 有也不支持 |
| canCloseWithEscape | 布尔，可选 | 原生；true 允许 Esc，false 禁止 |
| afterAction | 字符串，可选 | 原生；CLOSE/NONE/WAIT_FOR_RESPONSE，例 CLOSE；不是限购提交开关 |
| pause | 布尔，可选兼容 | 实际永远 false；true 留原字段并给 SERVER_PAUSE_IGNORED，不暂停服务器；网页已移除 |
| shortcutEnabled | 布尔，可选 | 仅 true 启用唯一主菜单；省略/null/false 不启用，保存检查全集唯一 |
| body | BodyBlock 数组，可选 | 省略/null/[] 都没有正文；顺序展示，元素不能 null |
| inputs | InputDefinition 数组，可选 | 省略/null/[] 无输入；全菜单 key 唯一，元素不能 null |
| noticeAction | 按钮，可选，仅 notice | 省略使用 Paper notice 自带按钮；显式配置须有效 id/label |
| yesButton | 按钮，confirmation 必填 | 其他菜单不可配置；不是 yesButton.actions 的缩写 |
| noButton | 按钮，confirmation 必填 | 其他菜单不可配置；通常 actionType close |
| actions | 按钮数组，multi_action 必需非空 | notice/confirmation 中即使 [] 也不可配置；与按钮内动作链不同 |
| exitAction | 按钮，可选，仅 multi_action | 原生退出槽位；不是普通列表元素位置 |
| columns | 正整数，可选，仅 multi_action | 后端允许 1～2147483647，未设交原生；网页限制 1～6。建议 1/2/3，不保证超大值可用 |
| metadata | 对象，可选，服务器管理 | 保存由服务器维护；教学菜单省略，不手改 |
| metadata.created | 带时区 ISO-8601 字符串，可选 | 创建时间，例如 2026-10-16T00:00:00Z；不是限购起点 |
| metadata.updated | 带时区 ISO-8601 字符串，可选 | 最近正式更新时间；由服务器维护 |
| metadata.version | 正整数，可选 | 菜单业务版本；不是 schemaVersion 或 HTTP revision |
| metadata.author | 字符串，可选 | 作者说明；不是权限或登录身份 |
| buttonWidth | 整数，当前不支持 | 即使 POJO 保留，非 null 会拒绝；不作为全局按钮宽度 |
| dialogs | DialogReference 数组，当前不支持 | menuId/registryKey 对象用于模型保留，非 null 拒绝；导航应使用业务 open_menu |

除 notice 外的按钮 ID 在全菜单槽位内也必须唯一；同一菜单不能同时填写多个类型的槽位。没有 root.permission、x/y 或任意布局节点。

## TextSpec（所有文字字段共用）

| 形式/字段 | 类型与要求 | 缺省与用途 |
| --- | --- | --- |
| 字符串简写 | 字符串，如 `'&a欢迎'` | **legacy** 解析，不是 MiniMessage；不写 format/value |
| format | 对象形式必填字符串 | plain / legacy / minimessage / component，没有隐式默认 |
| value | 对象形式必填 | 前三种是字符串；component 是 Adventure JSON 组件值，需可由固定序列化器解析；不能为 null |

plain 原样显示 & 和标签；legacy 支持标准 &0～&f、&k～&o、&r，以及固定版本已测试的 `&#RRGGBB` 和 `&x&R&R&G&G&B&B` Hex，例如 `&#123456文字`、`&x&1&2&3&4&5&6文字`。§ 不是此 legacyAmpersand 解析器的控制符。颜色代码重置之前样式，&r 重置；MiniMessage 也可用 `<#55FF55>文本</#55FF55>`。转到新格式不直接重解释旧源码；网页不开放 legacy 混淆样式 &k 的可视化转换。

MiniMessage 支持后端标准解析；网页仅已验证颜色、粗/斜/下划线/删除线、换行/重置、多色 gradient 的无损子集。复杂标签/交互及 Component 原文保护，不提供源码按钮。Component 中不能保存网页 HTML 或 TipTap JSON。内部 shorthand 标记不属于菜单字段。

示例对象：`{format: plain, value: 'A & B'}`；`{format: minimessage, value: '<gradient:#55FFFF:#FF55FF>彩色</gradient>'}`。梯度色均匀分布；网页尽量匹配固定 Adventure，不承诺与游戏像素相同。

## BodyBlock

共同 `body[i].type` 必填 text/item，不接受额外未知字段。

| 路径（相对正文项） | 类型/必填 | 缺省/null、范围、用途与例 |
| --- | --- | --- |
| text（text） | TextSpec，必填 | 如 `'欢迎'` |
| width（text） | 整数，可选 | 原生，1～1024；例 300 |
| colorMode / color（text） | 旧字符串，当前不支持 | 非 null 拒绝，颜色放 TextSpec；不是 plain 的颜色属性 |
| material（item） | 注册名字符串，必填 | 捕获的实际物品集合；如 OAK_LOG/minecraft:oak_log |
| amount（item） | 整数，可选 | 默认 1，1～2147483647；展示数量，原生/材质堆叠还可能限制，不是发货表达式 |
| name（item） | TextSpec，可选 | 不设置自定义展示名；null 同未配置 |
| lore（item） | TextSpec 数组，可选 | 省略/null 无自定义 lore，[] 空；每项非 null，悬停说明 |
| glow（item） | 布尔，可选 | 未设不覆盖原生闪光，true 强制展示闪光、false 关闭；不赋予真实附魔 |
| description（item） | text 正文对象，可选 | 物品旁说明，text 必需、width 可选；type 可省略/写 text，不支持再嵌 item |
| showDecorations（item） | 布尔，可选 | 原生；是否显示物品附加装饰 |
| showTooltip（item） | 布尔，可选 | 原生；悬停提示 |
| width / height（item） | 整数，可选 | 原生，各 1～256；例 24/24 |

正文 item 没有 enchantments/attributes 等发货字段；它的 name/lore/description 不参与实际交易物品原型。

## InputDefinition

共同字段：`inputs[i].type` 必填 bool/text/number_range/single_option；`key` 必填 `[A-Za-z0-9_.-]{1,128}` 且本菜单唯一；`label` TextSpec 必填。输入显示和业务验证分开，客户端回传须再次校验。

| 类型/字段 | 类型/必填 | 缺省/null、约束、单位与例 |
| --- | --- | --- |
| bool.initial | 布尔，可选 | 原生；教程显式 false |
| bool.onTrue / onFalse | 字符串，可选 | 原生序列化值；普通教程不用改，业务确认按已解析的 bool 比较 |
| text.initial | 字符串，可选 | 原生；例 `''` 或 numeric=true 时 `'1'`，不是数值 1 |
| text.width | 整数，可选 | 原生，1～1024 |
| text.maxLength | 整数，可选 | 验证初始值时缺省按 32；1～2147483647，初始字符串长度不得超过它 |
| text.labelVisible | 布尔，可选 | 原生，是否显示标签 |
| text.multiline | 对象，可选 | 普通文本多行；maxLines 正整数可选，height 整数 1～512 可选；只指定需要的约束 |
| text.numeric | 布尔，可选 | 仅 true 启用有限 double 输入校验；省略/null/false 为普通文本 |
| text.integer | 布尔，可选，仅 numeric | true 要求整数；false 不强制；未启用 numeric 不得配置 |
| text.minimum / maximum | 有限数，可选，仅 numeric | 未设取 -Double.MAX_VALUE / Double.MAX_VALUE；minimum≤maximum，例 1/64 |
| text.inventoryMaximumMaterial | 注册名，可选，仅 numeric | 打开/刷新时最大值被匹配普通库存截断；可连同 minimum/initial 调整，非定时实时更新 |
| number_range.start / end | 有限数，必填 | 可转有限 float，start≤end；例 1/64，不是最大库存保证 |
| number_range.initial | 有限数，可选 | 未设交原生，显式必须在 start/end 范围 |
| number_range.step | 有限数，可选 | 未设交原生；>0，发货数量建议 1 |
| number_range.width | 整数，可选 | 原生，1～1024 |
| number_range.labelFormat | 字符串，可选 | 原生，须接受 Java String.format 的标签字符串/数值参数，例 `'%s: %.0f'` |
| number_range.inventoryMaximumMaterial | 注册名，可选 | 打开/刷新时按主背包 storageContents 中相似普通物品数量截断 end，并调整 start/initial；不含装备/副手/其他容器 |
| single_option.width | 整数，可选 | 原生，1～1024 |
| single_option.labelVisible | 布尔，可选 | 原生 |
| single_option.options | 选项数组，必需非空 | 元素不得 null，options[j].id 必填合法键且唯一 |
| options[j].display | TextSpec，可选 | 未设传原生空 display，按原生显示；建议显式中文 |
| options[j].initial | 布尔，可选 | true 为初始项，最多一个；未设/null/false 不标记，零个时交原生 |

numeric 文本不允许 multiline；其初始值必须能解析并满足数值/整数/范围。库存上限只是展示/输入绑定，不代替 take_item 真正执行时匹配检查。没有数组下标/HTML 属性等编辑器临时字段。

## 相关章节

[条件与动作](conditions-actions.md) · [API 契约](../../../WEB_API.md) · [固定 API](../../../PAPER_DIALOG_API_REFERENCE.md)

---

[上一章：06.1 可运行示例索引](examples.md) · [下一章：06.3 按钮、条件与动作完整参考](conditions-actions.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
