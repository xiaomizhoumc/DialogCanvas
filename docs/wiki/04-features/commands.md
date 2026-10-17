# 04.3 玩家、OP 与控制台命令

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 知道命令会真实改变服务器。

## 网页填写

添加“执行命令”，在“完整命令”输入 `/minecraft:gamemode survival {player}`，选择执行身份。网页接受开头 `/` 并拆成模型；YAML/JSON 使用 `command: minecraft:gamemode` 和 `arguments: ['survival', '{player}']`。不要在 YAML command 中塞整行。

| 身份 | 权限和上下文 | 示例 |
| --- | --- | --- |
| player | 点击者现有权限、玩家上下文 | 玩家有权使用的 rtp；具体由目标插件判断 |
| op | 同步分发期间临时 OP，finally 恢复原状态 | 管理操作；仍受目标插件实际 OP/权限语义影响 |
| console | 服务器控制台，没有玩家坐标或背包 | `minecraft:gamemode survival {player}` 显式目标 |

本插件不另设命令允许列表，也不代替目标插件判断权限。OP 不意味着目标插件一定认可所有权限组规则；其他插件延迟任务不保证延续临时 OP。选择器、`*` 是否有效由目标命令决定，`*` 不是原版通用“自己”；用 `{player}` 指点击者，`{uuid}` 为 UUID。

## 参数与安全边界

固定片段支持空格、坐标、选择器和 JSON；command 必须固定小写标识（可命名空间），不能由玩家输入决定。`$(key)` 动态参数仅允许受限单 token，不能注入空格、换行或新的选择器/JSON片段；固定参数片段最多 2048 字符，整条最多 8192 字符，禁止控制字符。

命令在可逆状态提交后执行，无法通用撤销。目标命令报错/无权限不代表扣款和发货已回滚；出现提交后失败先核查，不能自动重放。给强权限命令按钮加权限条件，是服主的菜单设计选择。

## 相关章节

[命令示例](../../../examples/wiki/menus/wiki_07_commands.yml) · [动作参考](../06-reference/conditions-actions.md)

---

[上一章：04.2 权限、条件树与错误](conditions.md) · [下一章：04.4 Vault 经济与买卖](commerce.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
