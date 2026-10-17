# 04.5 PAPI 变量与失败处理

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 已区分输入引用与第三方变量。

PlaceholderAPI 为可选依赖，编译版本 2.12.3。只有 PAPI 本体不保证任何特定变量可解析；还需对应提供者/扩展。先在测试服用 PAPI 的解析命令检查实际输出，例如已提供对应扩展时 `/papi parse me %vaultunlocked_eco_balance%` 应为纯数字，不是原样 `%...%`。该例属于特定 VaultUnlocked 环境，不保证 `%vault_eco_balance%` 自动可用。

当前消费位置：条件中的 placeholder 来源/叶节点，以及 **message 动作**。标题、正文、按钮 label/tooltip 不隐式解析 PAPI；数值表达式和命令参数也不是任意 PAPI 模板。没有新注册的 DCMenu PAPI expansion，不能臆造 `%dcmenu_amount%`。

普通不含 `%` 的消息无需 PAPI。含百分号的解析路径严格检测输出残留 `%`：扩展缺失、异常、null、未替换或字面 `50%` 都可能失败；目前没有百分号自动转义方案。API/日志仅记录错误码和位置，不输出输入、占位符结果或异常原文。

条件解析失败为 ERROR，成功/拒绝业务都不执行；message 中解析位于提交后，失败为 POST_COMMIT_FAILED，状态可能已经成交，不得重新点击。两种位置的失败策略不能混用。

## 相关章节

[变量参考](../06-reference/variables.md) · [占位符审计](../../../PLACEHOLDER_FAILURE_AUDIT.md)

---

[上一章：04.4 Vault 经济与买卖](commerce.md) · [下一章：04.6 普通物品、自定义附魔与属性](items.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
