# 商店物品与常用选项

## 本轮更新

- 动作声音选择保留 19 种常用音效：经验、升级、铁砧、按钮、门、箱子、村民及物品提示，显示中文名称。候选项与服务端能力取交集；已有其他有效声音保留并显示为“已配置的其他声音”，不会悄悄替换。没有打包音频文件，音效由客户端提供。
- 正文物品、按钮图标、背包上限和动作物品均直接输入注册名，不再使用图片选择、搜索或分类。全部 1047 张缩略图 PNG 已从源资源及当前 JAR 移除。网页显示文字占位，游戏纹理由客户端提供；原有 1423 项 Sprite 映射及旧菜单保持兼容。并非所有物品都有按钮 Sprite；未知项保留按钮文字并提示。
- 给物品、收取物品使用注册名文本输入，可填 `iron_ingot` 或 `minecraft:iron_ingot`；兼容旧 `IRON_INGOT`。输入后显示中文原版名称，保存由服务器最终校验。

## 自定义发货

沿用 `give_item` / `take_item`，增加以下可选字段，同一份配置在 JSON、YAML 与网页间往返：

| 字段 | 用法 |
| --- | --- |
| `material` | 实际服务端注册名（常见为原版名称）；未知项拒绝，不生成外观相似的普通替代物品 |
| `name` | TextSpec 名称；留空不设置自定义名称 |
| `lore` | TextSpec 数组，每项一行描述 |
| `enchantments` | 附魔注册名到等级的映射，如 `{"minecraft:sharpness": 10}`；1～255 整数，最多 32 项，服主可显式设置超原版上限及不兼容组合 |
| `attributes` | 额外属性列表，最多 32 项；保留原物品默认属性 |
| `unbreakable` | 开启后不消耗耐久 |

每项属性有 `attribute`、`amount`、`operation`、`slot`：例如攻击伤害 `minecraft:attack_damage`，100，`ADD_NUMBER`，`mainhand`。这是主手额外增加 100，附魔伤害另计。`ADD_SCALAR` 的 0.1 表示按基础值额外增加 10%；`MULTIPLY_SCALAR_1` 的 0.1 表示最终乘以 1.1。省略计算方式为直接增加，省略部位为主手；数值必须有限且绝对值不超过 1000000。具体注册名按 `/api/meta` 的 `itemRegistries` 校验。

真实附魔自然产生光效。旧 `give_item.glow` 仍兼容读取但不用于伪造发货光效，正文展示的光效也不影响发货。按钮 Sprite 不支持附魔闪光。

`take_item` 精确匹配同一原型的名称、描述、附魔、属性、不可损坏等元数据；普通回收不会收走自定义装备。耐久、附魔或名称已变化的物品不再匹配全新商品，不作宽泛回收。

## 插件与模组物品边界

纯 Paper 没有统一的“任意插件物品名称 → 实物”协议。其他插件的私有物品名通常不是服务端的物品注册名，其功能还可能依赖专用元数据。此版直接通过固定 Paper API 的 Registry.ITEM 查找并创建，不新增外部物品接入口；实际注册表中存在的名称才可使用，未知注册名在校验时给出定位错误。模组物品需要相应服务端实际注册和创建能力，不能仅换一个字符串；本轮仅在标准 Paper 验证，不承诺混合服务端兼容。

## 示例

- `examples/custom_weapon_shop.json` 与带中文注释的 `.yml` 是同一菜单。
- 新菜单可将 YAML 放到插件的 `menus` 目录，控制台执行 `dcmenu reload custom_weapon_shop` 导入。确认没有同名冲突；不要直接覆盖自己的正式数据或同步基线。
- 游戏 OP 输入 `/dcmenu open custom_weapon_shop`。示例会实际扣 1000 游戏币并发一把武器，每人每天限购一把，请在测试环境使用。
- 本轮未修改、部署原服务器；客户端伤害计算、真实经济插件交易仍需服主验证。

## 固定 API 核对

实现按项目缓存的 Paper 26.1.2 Build 74 API 工件通过 javap 核对：`Registry.ITEM.get(NamespacedKey)`、`ItemType.createItemStack()`、`ItemType.getDefaultAttributeModifiers()`、`ItemMeta.addEnchant(Enchantment,int,boolean)`、`ItemMeta.setUnbreakable(boolean)`、带 NamespacedKey/EquipmentSlotGroup 的 AttributeModifier 构造器。注册表读取与物品创建均在主线程；异步校验仅使用捕获后的不可变集合，不新增主线程文件 I/O。
