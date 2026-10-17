# 03.3 从最小菜单到完整菜单

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 最小例已成功导入；了解 YAML 列表。

## 按顺序学习和导入

1. 提示菜单：导入 `wiki_01_notice.yml`，只改标题，reload，网页重新读取。
2. 确认菜单：导入 `wiki_02_confirmation.yml`，观察 yes/no 两个固定槽位。不要把 actions/columns 加到 confirmation 根下。
3. 导航：前两份存在后导入 `wiki_03_navigation.yml`，两列按钮分别打开目标，退出按钮单独设置。全套教学目录为有向无环依赖，可按文件序号导入。
4. 输入：导入 `wiki_04_inputs.yml`，学习 bool/text/number_range/single_option；key 不是显示文字，改名须同步动作/条件引用。
5. 样式：导入 `wiki_05_text_icons.yml`，正文物品 `description` 同排说明；label/tooltip 用 TextSpec，icon 独立。
6. 条件：`wiki_06_conditions.yml` 展示 AND/OR、negate 字段与五种叶节点；PAPI 按钮需要对应扩展，玩家数据检查先用第十例初始化。
7. 命令：`wiki_07_commands.yml` 分 player/op/console，真实分发但仅发送教学消息，不在文档验证中执行。
8. 商店：`wiki_08_shop.yml` 先扣款再发货；出售先收物品再充值。先准备测试服经济服务，不以字符串校验通过代表已成交。
9. 限购：`wiki_09_limits.yml` 五周期和独立冷却；点击会真实给石头，不是网页预览。
10. 玩家数据：`wiki_10_player_data.yml` 初始化/增减/检查自己的 wiki.* 键；与限购独立，不按离线天数删除。

每份是完整 YAML，详细注释就在文件中；不使用省略号替代必需字段。教学原件放在独立 `examples/wiki/menus/`，不要修改原件来维护生产状态。插件重新导出不保留注释，请另存教学原件。

## 从“配置正确”到“功能正确”

解析成功只说明 YAML 合法；模型校验还检查 ID/类型/引用/范围。保存成功说明正式文件和注册表已提交；第三方权限、PAPI 输出、余额、背包及客户端渲染仍依赖运行环境。先校验、保存/重读，再小额测试，保留实际变化核对记录。

## 相关章节

[示例索引](../06-reference/examples.md) · [字段参考](../06-reference/menu-fields.md)

---

[上一章：03.2 JSON、YAML、同步与版本](storage.md) · [下一章：04.1 导航与主菜单快捷入口](../04-features/navigation.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
