# 06.1 可运行示例索引

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 了解复制 YAML 不等于正式提交。

本轮教学原件独立放在 examples/wiki/menus，ID 均为 wiki_*，不自动安装，不覆盖既有 examples_* 或用户菜单。已有同名 ID 时先改教学文件 ID/同名文件名，不覆盖。

| ID/文件 | 学习内容 | 依赖及实际影响 |
| --- | --- | --- |
| [wiki_01_notice](../../../examples/wiki/menus/wiki_01_notice.yml) | 最小提示 | 无，只有展示/关闭 |
| [wiki_02_confirmation](../../../examples/wiki/menus/wiki_02_confirmation.yml) | 确认/取消 | 只发消息 |
| [wiki_03_navigation](../../../examples/wiki/menus/wiki_03_navigation.yml) | 两列、退出、导航 | 先导入 01/02；只打开界面 |
| [wiki_04_inputs](../../../examples/wiki/menus/wiki_04_inputs.yml) | 四种输入、数量消息 | 只发消息，不保存备注 |
| [wiki_05_text_icons](../../../examples/wiki/menus/wiki_05_text_icons.yml) | 图标、颜色、渐变、tooltip、正文闪光 | 只展示，不发剑 |
| [wiki_06_conditions](../../../examples/wiki/menus/wiki_06_conditions.yml) | 五种条件及嵌套 | 余额按钮需对应 PAPI 扩展；数据检查先初始化第十例 |
| [wiki_07_commands](../../../examples/wiki/menus/wiki_07_commands.yml) | player/op/console | 真分发，只发教学消息；OP/console 按钮有管理员条件 |
| [wiki_08_shop](../../../examples/wiki/menus/wiki_08_shop.yml) | 原木买卖、额度、冷却 | 需要经济桥/提供者；真改余额/背包 |
| [wiki_09_limits](../../../examples/wiki/menus/wiki_09_limits.yml) | 五周期及独立冷却 | 真给石头；不扣钱 |
| [wiki_10_player_data](../../../examples/wiki/menus/wiki_10_player_data.yml) | 写入、增减、数据条件 | 真写点击者 wiki.* 业务数据 |

建议逐个“浏览器导入 → 核对预览 → 确认草稿 → 保存”，或复制到插件根 menus 再 reload。完整集合先经生产解析/校验器验证；实际首次导入按序，目标 01/02 在 03 之前。不要同时复制 JSON 与 YAML 后猜基线；本包只提供 YAML，无伪造 metadata/同步记录。

旧 [feature-menus](../../../examples/feature-menus/README.md)、[vanilla-shop](../../../examples/vanilla-shop/README.md) 是另有安装语义的历史功能包；不要把其“整包 JSON/基线停服安装”与本教程逐项导入混用。本轮不会执行真实商品/命令动作。

## 相关章节

[示例安装说明](../../../examples/wiki/README.md) · [YAML 教程](../03-yaml/tutorial.md)

---

[上一章：05.5 已知限制与人工验收](../05-operations/limits.md) · [下一章：06.2 菜单、TextSpec、正文与输入字段](menu-fields.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
