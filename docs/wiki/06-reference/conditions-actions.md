# 06.3 按钮、条件与动作完整参考

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 菜单字段与严格类型；表中 B 表示任一按钮槽位。

## 空值约定

延用菜单字段表：可选字段省略/null 通常表示不配置；必填 null 报错。Map 的 null 值、列表 null 元素不能用来“禁用一项”。未实现字段即使 POJO 保留也不代表可保存。动作没有嵌套动作列表，只有按钮的成功 actions 和 denyActions 两条有序链；条件可递归。

## ActionDefinition 按钮

| 路径 | 类型/必填 | 缺省/null、约束、用途与例 |
| --- | --- | --- |
| B.id | 字符串，必填 | 合法键 `[A-Za-z0-9_.-]{1,128}`，全菜单按钮唯一，例 buy |
| B.type | 字符串，可选 | 未设合法，显式仅 button；不是业务动作 type |
| B.label | TextSpec，必填 | 按钮文字，例 `'&a购买'` |
| B.tooltip | TextSpec，可选 | 原生悬停说明，不是网页 ! 用法提示 |
| B.width | 整数，可选 | 原生，1～1024 |
| B.icon | 对象，可选 | 不设置则无图标；icon.type 缺省 item（**显式 null 不同：无效图标警告**），当前只 item；icon.material 为已映射注册名，缺失/无映射警告降级保留文字 |
| B.icon.type | 字符串，可选 | 缺省 item；只支持 item，其他值/显式 null 降级警告 |
| B.icon.material | 字符串，配置图标时填写 | 例如 BONE；必须在 iconMappings 中，无效时不显示图标，文字保留 |
| B.actionType | 字符串，可选 | 默认 custom；custom 处理业务点击，close 只关，none 无回调。close/none 的业务配置不要期待执行 |
| B.actionId | 字符串，可选 | 命名空间键 `[a-z0-9_.-]+:[a-z0-9/._-]+`；兼容保留并校验，当前点击绑定不使用它路由，不是条件类型 |
| B.callbackOptions | 对象，可选 | uses 正整数可选；lifetime 正 ISO-8601 Duration 如 PT5M 可选；未设由固定 Adventure 回调策略决定，不自造默认无限次数 |
| B.callbackOptions.uses | 正整数，可选 | 可点击次数，如 1；省略使用原生策略 |
| B.callbackOptions.lifetime | 字符串，可选 | 正 ISO-8601 Duration，如 PT5M；省略使用原生策略，不接受裸数字秒 |
| B.conditions | ConditionDefinition，可选 | 省略/null 通过；错误不是 FAIL |
| B.actions | ActionStep 数组，可选 | 省略/null/[] 空成功链，不因存在文字/图标自动扣款 |
| B.denyActions | ActionStep 数组，可选 | 正常 FAIL 执行；ERROR 不执行 |
| B.limit | 规则名→LimitDefinition 对象，可选 | 空/未配置无限额；每项规则名合法，值非 null |
| B.cooldown | 规则名→数值对象，可选 | 同名限额配对；非负整数秒，有限且可加至 Instant、不溢出；0 无等待，值不得 null |
| B.refreshAfterAction | 布尔，可选 | 仅 true 在成功提交后刷新正式菜单；新显示会重置输入/确认 |
| B.template / clickEvent / additions | 当前不支持 | 任意非 null 拒绝；StaticClickDefinition.action/value 虽有模型不开放配置 |

无效 icon 是 warning 而非自动替换材质；真正 give/take 的未知注册名是校验错误。教程显式配置按钮 id，即使原生 API 不要求也不能省略本插件标识。

## ConditionDefinition

| 路径/类型 | 字段、类型、必填及允许值 | 缺省/示例/边界 |
| --- | --- | --- |
| 分组 | 不写 type；operator 字符串 AND/OR，items 非空条件数组 | items 从上到下短路；不混叶节点字段，null 元素错误 |
| 共用 negate | 可选布尔 | 默认不取反；true 只反 PASS/FAIL，不反 ERROR |
| permission | type 必填 permission；permission 非空字符串 | 例 `shop.buy`，无动态变量插值 |
| placeholder | type=placeholder；placeholder 非空字符串；operator；value 字符串必填；comparison 可选见下 | 例 `%vaultunlocked_eco_balance%` / `'>='` / `'16'`；依赖真实扩展 |
| player_data | type=player_data；key 非空字符串；operator；value 字符串必填；comparison 同上 | 例 wiki.flag / `'=='` / ready；缺失键 ERROR |
| numeric | type=numeric；left/right ValueSource 必填；operator 数值比较 | `== != > >= < <=`；统一有限 double，不设置 comparison |
| string | type=string；left/right ValueSource 必填；operator 字符串比较 | `== !=`；精确区分大小写，不设置 comparison |

简便 placeholder/player_data 的 comparison 必须 numeric/string；仅 `> >= < <=` 可省略并推断 numeric，`== !=` 必须明确。建议总显式配置。value 始终字符串；numeric/string 的 literal 来源则可用 JSON 标量。数值不接受 null、对象/数组、无穷/NaN；数值字符串支持十进制及科学计数，不把布尔当 1/0。string 来源可把布尔转 true/false、数值按 double 字符串处理，比较数值应使用 numeric。

| ValueSource 字段组合 | 类型/必需 | 用法 |
| --- | --- | --- |
| source: literal + value | source 字符串，value 非 null JSON 标量 | 不加 key/placeholder；例 `{source: literal, value: 2}`；空字符串也是文字值 |
| source: input + key | 字符串 key 必填 | 菜单中已有输入 key；`amount`，不是 `$(amount)` |
| source: player_data + key | 字符串 key 必填 | 本插件业务键；不加 value/placeholder |
| source: placeholder + placeholder | 字符串必填 | 完整 `%...%` 变量，不加 key/value |

条件树最大深度 32、节点 1024，完整模型树 64。FAIL 允许拒绝链；ERROR 会 WARN（解析层/引擎去重）并禁止成功/拒绝链。未访问的短路动态分支不额外执行；静态结构错误仍在运行前验证。

## ActionStep 共用字段

`B.actions[i]` 与 `B.denyActions[i]` 规则相同：type 必填下表之一；delay 可选有限非负整数 tick（20 tick 名义一秒，服务器卡顿时非精确墙钟），缺省 0；chance 可选数 0～1，缺省 1。未命中跳过。return 正常提交此前动作，不是回滚。

| type | 专用字段/类型/必填 | 缺省/范围/语义与例 |
| --- | --- | --- |
| message | text: TextSpec 必填 | 状态提交后发；支持数值消息模板及实际 PAPI 消费，不代表标题也替换 |
| command | command 字符串必填；arguments 字符串数组可选；executor 字符串可选 | 默认 player；player/op/console。名称固定小写可命名空间、不含 / 和空格；参数省略/null/[] 无参数；固定片段非空≤2048、整条≤8192、无控制字符；动态值受限 token |
| economy_withdraw | amount 表达式字符串必填 | 有限正数；如 `'16 * $(amount)'`，Vault 扣款，可逆补偿 |
| economy_deposit | amount 表达式字符串必填 | 同上，充值，可逆补偿；无经济服务不能成功 |
| give_item | material/amount 必填，详见下表 | amount 有限正整数≤2147483647；注册名在实际服务端存在，事务发货 |
| take_item | 同 give_item | 按完整元数据原型收取；不足拒绝，成功后可补偿 |
| player_data_set | key 合法键必填；value JSON 值必填 | key `[A-Za-z0-9_.-]{1,128}`；对象/列表/标量可用；JSON null 可被模型作为 NullNode 保存，不作为删除指令；不支持文件路径 |
| player_data_increment | key 合法键必填；amount 表达式字符串必填 | 有限增量，可负；未配置无默认量；生产适配器对缺失键从 0 起算，已有值必须可数值更新；与条件读取缺失键的 ERROR 规则不同 |
| play_sound | sound 字符串必填；volume/pitch 数可选 | sound 来自服务端 Sound 常量能力集合，网页常用子集中文选择；volume/pitch 未设各为 1，显式非负可转有限 float；提交后声音不能撤回 |
| open_menu | menuId 字符串必填 | 已存在合法 ID，可自身引用；成功和拒绝链均校验目标；提交后展示 |
| close_menu | 无专用字段 | 提交后关闭，与按钮 actionType=close 的无业务路径不同 |
| refresh_menu | 无专用字段 | 提交后刷新当前菜单，输入重置 |
| return | 无专用字段 | 正常结束链；return 之后动作不执行，不强制补偿 |

可逆状态和提交后副作用分阶段执行，每一阶段按原列表顺序。延迟/概率适用当前动作的执行/收集策略，不保证混合列表保持交错。无限自动重试、嵌套动作、脚本表达式、任意玩家输入拼接命令名称均未提供。

## give_item / take_item 物品原型

| 专用路径 | 类型/必填 | 缺省/null、范围、示例与限制 |
| --- | --- | --- |
| material | 注册名字符串，必填 | iron_ingot / minecraft:iron_ingot，兼容 IRON_INGOT；未知名拒绝，不加载插件私有物品 |
| amount | 表达式字符串，必填 | 正整数有限；如 `'$(amount)'`，不是根正文的整数 amount |
| name | TextSpec，可选 | 未设不自定义名称；影响 take 的精确匹配 |
| lore | TextSpec 数组，可选 | 未设不加，[] 空；元素非 null；影响匹配 |
| enchantments | 注册名→整数映射，可选 | 1～255、最多32项，同一附魔不重复简写/全名；允许超原版/不兼容组合，例 sharpness: 5 |
| attributes | 属性对象数组，可选 | 最多32项，保留默认属性并增加；元素非 null |
| attributes[i].attribute | 注册名字符串，必填 | 来自 meta.itemRegistries.attributes，如 minecraft:attack_damage |
| attributes[i].amount | 有限数，必填 | 绝对值≤1000000；不是表达式，如 100 |
| attributes[i].operation | 字符串，可选 | 默认 ADD_NUMBER；另有 ADD_SCALAR、MULTIPLY_SCALAR_1 |
| attributes[i].slot | 字符串，可选 | 默认 mainhand；any/mainhand/offhand/hand/feet/legs/chest/head/armor/body/saddle |
| unbreakable | 布尔，可选 | true 设置不可损坏；未设不改原型默认，不等于无限堆叠 |
| glow | 布尔，历史兼容 | give 忽略伪造光效，真实附魔自然闪光；take 历史匹配可使用，别从正文 glow 推导发货 |

## LimitDefinition：B.limit.<规则名>

| 字段 | 类型/必填 | 缺省/null、范围、单位与例 |
| --- | --- | --- |
| player | 整数，可选 | -1 不限（省略/null 同），0 禁购，正整数额度 |
| server | 整数，可选 | 仅 -1 或省略/null，全服限购未实现 |
| reset | 字符串，规则对象存在时必填 | DAILY/WEEKLY/MONTHLY/NEVER/TIMED，大小写精确 |
| timedSeconds | 长整数，仅 TIMED 必填 | 正秒且加至 Instant 不溢出；非 TIMED 不得配置 |
| scope | 合法键字符串，可选 | 同菜单按钮共享身份；例 oak_daily，不跨菜单共享 |

`B.cooldown.<同规则名>` 是非负整数秒，默认无冷却。多个 limit/cooldown 规则一并预留/持久化；重启保留。额度单位是成功链 give_item 总数量，无发货时1；限额发货不能 chance≠1 或处于 return 后。缺省/null 不是“忽略已经存在的 pending 记录”，恢复保护始终生效。

## 相关章节

[变量](variables.md) · [事务](../04-features/data-transactions.md) · [示例](examples.md)

---

[上一章：06.2 菜单、TextSpec、正文与输入字段](menu-fields.md) · [下一章：06.4 变量与数值表达式参考](variables.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
