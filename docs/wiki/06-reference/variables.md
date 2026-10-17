# 06.4 变量与数值表达式参考

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 知道值来源、消息文本与命令参数是不同消费位置。

| 使用位置 | 正确语法/例 | 不支持或易混淆 |
| --- | --- | --- |
| 金额/发货数量/增量 | `'16 * $(amount)'` | amount 必须是 number_range 或 numeric=true 文本，不是任意 PAPI/脚本 |
| 条件 input 来源 | `{source: input, key: amount}` | key 不写 `$(amount)` |
| message 数值替换 | `'数量 $(amount)，总价 $(16 * $(amount))'` | 算式必须完整包在外层 `$()`；`16 * $(amount)` 只替换内部数量，其余字面显示 |
| command 动态参数 | `$(key)`、`{player}`、`{uuid}` | 玩家输入只能受限单 token，不当作任意完整命令/自由文本注入 |
| 条件 PAPI | placeholder 来源或简便叶的 `'%变量名%'` | 安装 PAPI 不保证存在该扩展，不默认0/true |
| message PAPI | text 中受支持的 `%...%` | 提交后失败不能撤回交易 |
| 标题/正文/按钮/tooltip | 当前静态 TextSpec | 不隐式解析 `$()` 或 PAPI，不虚构变量 |
| player_data_set.value | JSON 业务值 | 不执行占位符/数值脚本；普通字符串是数据 |

## 算术语法

有限 double 十进制常量、数值输入引用、`+ - * /`、括号及一元正负；最大长度1024、深度32。没有函数、幂、比较、JavaScript、玩家数据/PAPI 任意求值。除零/非有限结果失败，不回落为0；动态输入运行时再次校验。金额正、发货数量正整数、增量可负，常量在保存校验时就检查。

假设 amount=8：金额 `'16 * $(amount)'` 得128；消息 `'花费 $(16 * $(amount)) 游戏币'` 显示“花费128游戏币”；消息 `'花费16 * $(amount)'` 不会自动推导乘法。字符串简写中的 & 颜色与变量语法独立，配置引号按 YAML 规则处理。

没有任意文本输入回显消息模板；备注不能直接套成数值模板。标准 bool 与单选用条件来源读取；数值表达式不自动将它们变数字。需要新变量只能依据未来明确实现，不能自行在文档中注册假想 expansion。

## 相关章节

[PAPI](../04-features/placeholders.md) · [条件动作](conditions-actions.md)

---

[上一章：06.3 按钮、条件与动作完整参考](conditions-actions.md) · [下一章：06.5 错误码与诊断参考](errors.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
