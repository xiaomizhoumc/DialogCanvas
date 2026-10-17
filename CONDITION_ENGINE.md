# ConditionEngine — 步骤 3.2

状态：已接入生产动作入口；最新占位符错误处理和验证见 PLACEHOLDER_FAILURE_AUDIT.md。

步骤编号：3.1 为条件系统设计确认，3.2 为本文件记录的条件引擎实现。

## 调用与线程

- 入口：`dialogcanvas.engine.condition.ConditionEngine`。
- `evaluate(ConditionDefinition, ConditionContext)` 返回 boolean。
- `evaluateDetailed(...)` 返回 ConditionResult，提供 PASS/FAIL/ERROR、字段路径和错误说明。
- 调用方必须通过已有 Scheduler.sync() 在主线程求值；异步直接调用会抛出 IllegalStateException，不访问权限或来源适配器。
- ConditionContext 接收 Player、已经校验过的输入 JSON 值、PlaceholderResolver 和 PlayerDataView。构造时复制输入节点。
- PlayerDataView 必须读取预加载快照，不能在求值过程中进行文件 I/O。PlaceholderResolver 是主线程接口；步骤 3.6 已增加可选 PlaceholderAPI 适配器和 resolveResult 入口，详见 PLACEHOLDER_RESOLVER.md。

## 五种类型

| type | 字段 |
|---|---|
| permission | permission、可选 negate |
| placeholder | placeholder、comparison、operator、value、可选 negate |
| player_data | key、comparison、operator、value、可选 negate |
| numeric | left、operator、right、可选 negate |
| string | left、operator、right、可选 negate |

分组不设置 type，使用 operator=AND/OR 和非空 items。叶节点不能带 items，分组不能带叶节点字段。未配置根 conditions 表示没有限制；空分组判为配置错误。

ValueSourceDefinition 的 source 为 literal/input/placeholder/player_data；分别使用 value/key/placeholder/key。数值和字符串条件的 left/right 使用这个结构。

placeholder/player_data 保留原示例的便捷字段。其 value 为固定字符串，不解析变量和算术表达式；动态右值应使用 numeric/string 的来源引用。comparison 为 numeric/string；只有 >、>=、<、<= 可省略 comparison 并推断为 numeric，==、!= 必须明确指定。

## 求值规则

1. 完整校验树并构造本次求值的不可变节点，再读取任何运行时来源。短路跳过的分支仍须结构合法。
2. 按 items 从左到右递归。AND 遇正常 FAIL 停止，OR 遇 PASS 停止。
3. 当前节点正常求值后应用 negate。访问到的节点发生 ERROR 时立即终止本次求值，不取反、不继续 OR 分支；boolean 入口返回 false。
4. 每次求值独立缓存输入、占位符和玩家数据来源，缓存键同时包含来源类型和标识。重复来源只解析一次；不同次点击不会共享缓存。重复权限查询也在本次求值内复用。
5. 默认最大深度 32（根节点计为 1）、节点数 1024（包括分组和每次出现的叶节点），可通过 ConditionEngine.Limits 调整。超限或循环引用返回 ERROR。

## 审核点

### 占位符失败处理

Resolver 返回失败 Result、Optional.empty、未替换的百分号、返回 null 或抛出运行时异常，均返回 false/ERROR，并记录一次 WARNING。ConditionResult 包含错误码与字段路径；生产解析器已记录的失败不重复 WARN。有效字符串无法转为数值时同样失败并 WARN。

所有 ERROR 在统一出口记录 WARN；正常条件不满足不记录 WARN。错误不会通过 negate 变成 true。

### 数值类型转换

按用户最新要求统一为 double，替代早期设计中的 BigDecimal。

- 支持有限 JSON 数字和去除首尾空白的十进制字符串，允许科学计数法。
- 使用 ==、!=、>、>=、<、<= 比较；1 和 1.0 相等，正负零相等。
- 拒绝布尔值、空字符串、对象/数组、NaN、Infinity、十六进制浮点文本、带表达式的字符串和溢出为无穷大的数值。
- 采用 double 的精度与舍入语义，不增加 epsilon；0.1 + 0.2 一类运算不属于当前条件模块。购买价格与扣款策略在后续交易模块定义。

字符串比较支持 ==/!=，区分大小写，不去除空白。布尔值转换为 true/false；数字按 Double.toString 转为字符串；不会将缺失值转换成字符串 null。

### 嵌套逻辑正确性

MockBukkit 测试覆盖 AND/OR 嵌套、分组 negate、顺序和短路、错误传播、跳过分支的配置校验，以及每次求值的来源缓存。

## 验证

- ConditionEngineTest：39 项全部通过。
- 完整项目：67 项测试，64 通过、3 跳过、0 失败。三个跳过项属于原有动态 Dialog provider 测试，与条件引擎无关。
- Java 25 + Gradle 9.4.0 构建和 Shadow 打包成功。
- 构建日志：build-reports/condition-engine-build.log。
- 测试报告：plugin/build/reports/tests/test/index.html。

当前只完成条件系统。实际 DialogResponse 校验、PlaceholderAPI 接入、玩家数据文件存储和 PASS/FAIL/ERROR 对应动作执行仍待用户后续步骤安排；未替换正在运行的真机服务器 JAR。

## 阶段 7.5 审计补充

带位置的 evaluateDetailed 与 resolveResult 仍在主线程运行。自定义解析器返回 null、未解析成功值、RuntimeException 或 LinkageError 均成为 ERROR；错误描述只保留安全诊断码。生产 ERROR 阻断成功和 denyActions，不预留额度，并持久化 conditionError 回执。未访问的短路分支不解析、不告警；纯结构预校验仍检查整棵树。前文测试数量是步骤 3.2 历史记录，当前验证见审计报告。
