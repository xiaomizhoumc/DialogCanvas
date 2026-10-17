# 04.6 普通物品、自定义附魔与属性

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 先分清图标、展示和发货。

动作给物品/收物品使用注册名文本输入，如 `iron_ingot`、`minecraft:iron_ingot`；也兼容合法大写 Material。实际可用集合来自服务器物品注册表。名称找不到明确拒绝，不生成替代品。纯 Paper 不会仅凭 `someplugin:sword` 找到其他插件私有对象；目前没有对外物品提供者注册 SPI。

自定义动作物品支持 TextSpec name、lore、真实附魔映射 `enchantments`、attributes 和 unbreakable。附魔注册名如 `minecraft:sharpness`，等级 1～255，可显式超原版上限；最多 32 项，不重复简写/全名指同一附魔。属性如 `minecraft:attack_damage`，有限值绝对值不超过 1000000，最多 32 项。

属性 operation 为 ADD_NUMBER（直接加）、ADD_SCALAR（基础值比例加）、MULTIPLY_SCALAR_1（最终乘算）；缺省 ADD_NUMBER，slot 缺省 mainhand。额外属性保留原物品默认属性，例如 attack_damage 的 ADD_NUMBER=100 表示额外增加 100，附魔伤害另计；详见 [物品专题](../../../ITEM_CUSTOMIZATION.md)。

网页填法：动作选“给予物品” → “物品注册名”填 `diamond_sword` → 数量 `1` → 展开自定义物品设置，在“物品名称（选填）”填“教学之剑” → “添加附魔”，注册名填 `minecraft:sharpness`、等级 5 → 需要时增加属性。预期保存/重读保留这些字段；客户端伤害和真实发货需测试玩家验证。正文展示剑的 glow 不赋予发货剑真实附魔；give_item 的旧 glow 不制造无附魔闪光，真实附魔决定自然光效。take_item 旧 glow 只用于历史匹配兼容。

回收自定义物品需匹配完整原型，包括名称、描述、附魔/属性等；`inventoryMaximumMaterial` 仅按普通材质原型统计，不能当特殊商品鉴定器。不要把收物品规则写得比预期宽泛。

## 相关章节

[物品权威专题](../../../ITEM_CUSTOMIZATION.md) · [动作参数](../06-reference/conditions-actions.md)

---

[上一章：04.5 PAPI 变量与失败处理](placeholders.md) · [下一章：04.7 玩家数据、限购、冷却与事务](data-transactions.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
