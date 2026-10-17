# 04.2 权限、条件树与错误

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 已知输入 key、按钮点击方式。

## 求值顺序

生产入口先检查会话/玩家/输入与交易准入，再按条件树（其中 permission 叶节点按配置位置求值）、限购/冷却、动作执行。没有独立的根权限字段，不能宣称所有 permission 叶节点总被自动提到第一位；需要先权限后其他条件，就把权限放 AND 第一项。

分组不写 type，使用 `operator: AND/OR`、非空 items。AND 从上到下遇 FAIL 停；OR 遇 PASS 停。完整树先做结构校验，运行不为日志求值已短路的动态分支。最多深度 32、节点 1024。

## 五种叶节点

permission 检查点击者权限；placeholder 读取 PAPI；player_data 读本插件数据；numeric 比较两个数值来源；string 比较文本。numeric/string 的左右来源为 literal/input/placeholder/player_data，input 的 key 填 `amount`，不是 `$(amount)`。

数值统一有限 double；支持 `== != > >= < <=`，不是货币精确十进制引擎。string 只 `== !=`，区分大小写，不是正则/包含匹配。placeholder/player_data 简便叶节点 `value` 为字符串；等于/不等于必须显式说明 comparison，建议总写 numeric 或 string。

`negate: true` 只翻转正常 PASS/FAIL。权限例：拒绝有 `server.banned_shop` 的玩家，可对该 permission 叶设置 negate；不要用它掩盖 PAPI 故障。

## FAIL 与 ERROR

正常不满足是 FAIL，执行 denyActions；没有拒绝动作时给简短提示。变量缺失、非法数值、权限查询异常等是 ERROR：成功和拒绝链均不执行，不扣款/发货/提交额度，WARN 一次并带脱敏定位。ERROR 不会因 `!=`、negate、OR 变成通过；未访问的短路分支则没有运行错误。

## 相关章节

[条件字段](../06-reference/conditions-actions.md) · [占位符](placeholders.md)

---

[上一章：04.1 导航与主菜单快捷入口](navigation.md) · [下一章：04.3 玩家、OP 与控制台命令](commands.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
