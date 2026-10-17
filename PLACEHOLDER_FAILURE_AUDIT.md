# 阶段 7.5：占位符失败处理审计

日期：2026-10-15。使用当前固定 Paper Build 74、PAPI compileOnly 2.12.3 和现有 Result/ConditionEngine；没有改造菜单格式、命令权限、存储同步或交易提交顺序。原服务器数据未读取、修改、清理或部署。

## 确认的问题与修复

| 位置（相对于项目根） | 原问题 / 线程依据 | 处理结果 |
|---|---|---|
| `plugin/src/main/java/dialogcanvas/engine/condition/PlaceholderResolver.java:23` | 注入适配器可覆盖旧 resolveResult，绕过 null/残留标记检查；消息调用不能像条件入口那样防御 | 新增兼容的带位置重载，捕获 RuntimeException/LinkageError，校验 null Result 和成功值；保留 Optional 与旧 Result 入口 |
| `plugin/src/main/java/dialogcanvas/engine/condition/PlaceholderResolver.java:48` | 外部 error 文本及 record 自动 toString 可能泄漏输入/结果 | diagnosticCode 仅输出已知错误码，其他错误统一 PROVIDER_FAILED；Result 文本表示不含 value/error 原文 |
| `plugin/src/main/java/dialogcanvas/placeholder/PlaceholderApiResolver.java:33` | 失败 WARN 缺少条件/动作位置 | 使用调用方位置，日志仅错误码和安全位置；生产调用通过主线程，异步调用先返回 NOT_MAIN_THREAD，不查询 PAPI |
| `plugin/src/main/java/dialogcanvas/engine/condition/ConditionEngine.java:51` | 自定义解析器的 error 原文进入条件日志/结果；未知条件 type 原文进入诊断 | 消费诊断码；WARN 不拼接原文。结构错误不包含未知 type 值。继续在统一出口处理 ERROR |
| `plugin/src/main/java/dialogcanvas/engine/condition/ConditionEngine.java:214` | 自定义 resolveResult 可抛 LinkageError，绕过结构化条件结果 | 调用带位置的受检边界，并保留外层防御；任何访问到的来源错误立即终止整棵树 |
| `plugin/src/main/java/dialogcanvas/engine/production/ProductionActionEngine.java:92` | 条件 ERROR 的生产回执与普通 denied 混用 | ERROR 阻断成功和拒绝两个动作链，写 conditionError 终态与 code/path；不进入限购/冷却、不执行状态或副作用 |
| `plugin/src/main/java/dialogcanvas/engine/production/ProductionStore.java:19` | 新错误终态需要与重启恢复和持久化门锁一致 | 共用 TERMINAL 集合；错误回执原子写成功才解锁，写失败保留 pending 并跨重启阻断。不新增后台文件 I/O |
| `plugin/src/main/java/dialogcanvas/engine/TransactionalActionEngine.java:377` | 消息调用不防御 null、未解析成功结果与外部错误原文 | 共用受检解析边界，按 transaction/actions[index].text 定位；不发送残缺消息，保留 POST_COMMIT_FAILED 语义 |

纯 Result 校验与错误码处理不读 Bukkit。ConditionEngine、PAPI 和消息实际解析均在既有 Scheduler 主线程保证内，不重复调度；ProductionStore 沿用串行文件线程写回执。

## 求值和动作策略

1. 先对完整条件树做纯结构/常量校验，再按从左到右顺序访问运行时来源。结构非法即 ERROR，即使该分支本来可能被运行时短路；此校验不会调用 PAPI、权限或玩家数据。
2. AND 遇正常 FAIL 停止，OR 遇 PASS 停止。未访问的分支不读取、不记录 WARN。OR 的前项已 PASS 时，不会为了寻找错误而解析后项。
3. 已访问来源 ERROR 立即上抛至根，不继续 OR，不对叶/分组应用 negate。`!=` 只比较成功取得的值；错误不会成为“与任何值不相等”。
4. PASS 才能进入成功动作和限购；正常 FAIL 可执行配置的 denyActions，且本身不告警。ERROR 执行策略是两个分支都不执行，向玩家提示条件无法读取；不伪装为普通拒绝。
5. ERROR 在持久意图创建后、限购预留前发生；错误回执是 conditionError，附 conditionError.code/path，不保存扩展输出或外部错误描述。回执写失败仍 pending，保留交易阻止状态等待服主核查。
6. 消息中的 PAPI 在交易提交后解析，用于取得提交后的余额等显示值。失败停止该副作用链，结果为 POST_COMMIT_FAILED；已完成扣款、物品及限购不回滚、不自动重发或重试。条件阻断规则不套用到这一阶段。

## 分支覆盖表

以下为语义分支用例覆盖，不宣称使用 JaCoCo 测得所有字节码分支的覆盖率。P=PlaceholderApiResolverTest，C=ConditionEngineTest，B=ProductionActionEngineTest，A=TransactionalActionEngineTest，均使用 MockBukkit。

| 分支 | 预期 | 已执行用例 |
|---|---|---|
| PAPI 未安装/禁用 | UNAVAILABLE、WARN 一次、条件 ERROR | P.missingOptionalPluginFailsWithoutLinkingPapi、P.productionProviderFailureWarnsOnceWithSafeConditionLocation(absent) |
| PAPI 可用但扩展不存在/部分未替换 | UNRESOLVED_PERCENT、ERROR，不变空串/0 | P.anyRemainingPercentIsFailureAndWarnsOnce、P.productionProviderFailureWarnsOnceWithSafeConditionLocation(extension/partial) |
| 解析异常、链接失败、可用性检查异常 | PLACEHOLDER_EXCEPTION；不带异常 message | P.parserFailuresReturnResult、P.productionProviderFailureWarnsOnceWithSafeConditionLocation(exception/linkage/availability) |
| 后端返回 null；适配器返回 null Result | NULL_PLACEHOLDER_RESULT；ERROR | P.parserFailuresReturnResult(null)、C.providerFailuresCannotPassInequalityNegationOrNestedGroups(null) |
| 自定义 Result.ok 含未解析标记 | 重新校验拒绝，不发送文字 | C.providerFailuresCannotPassInequalityNegationOrNestedGroups(unchecked)、A.checkedMessageBoundaryPreservesCommitAndDoesNotLeakProviderData(unchecked) |
| 非法数字、NaN、无穷、溢出、空串 | INVALID_NUMBER；不作 0 使用 | C.rejectsInvalidNumericInputWithoutCoercingItToZero（既有参数用例）、C.providerFailuresCannotPassInequalityNegationOrNestedGroups(number/nan/empty)、P.productionProviderFailureWarnsOnceWithSafeConditionLocation(number) |
| 扩展合法返回空字符串 | 解析成功；字符串可正常比较，数值仍 ERROR | P.resolvedTextAndEmptyValueAreSuccessful、C.providerFailuresCannotPassInequalityNegationOrNestedGroups(empty) |
| 普通文本/PAPI 缺失 | 原样返回，不访问可选插件 | P.plainTextNeedsNoPapiAndSuccessHasNoWarning、A.ordinaryDisplayTextWithLiteralPercentDoesNotCallPapi |
| 正常值但条件不满足 | FAIL，无 WARN，允许普通 denyActions | P.ordinaryConditionMismatchDoesNotWarn、A.conditionFailureRunsDenyActionsAndErrorFailsClosed |
| 数值 !=、叶 negate、分组 negate、嵌套 AND/OR | 所有访问到的错误均 ERROR | C.providerFailuresCannotPassInequalityNegationOrNestedGroups（9 种失败 × 4 种结构）、C.unavailableStringComparisonCannotBeInverted |
| AND 前项 FAIL / OR 前项 PASS | 后项不可用来源调用数为 0，无 WARN | C.skippedUnavailableProviderDoesNotRunOrWarn、C.nestedGroupsApplyNegationAfterOrderedShortCircuiting |
| ERROR 后有本可 PASS 的 OR 分支 | 不继续后项，不变 PASS | C.runtimeErrorAbortsOrBeforeAnOtherwisePassingBranch、P.conditionFailsClosedUnderNegationAndOrWithoutDuplicateWarn |
| 同次求值多次引用 | 缓存一次，不跨点击缓存 | C.cachesEachSourceOncePerEvaluationButNotAcrossEvaluations、P.conditionUsesParsedValueAndCachesPerEvaluation |
| 生产 ERROR 成功/拒绝链与限购 | 余额/物品不变，两个动作链不执行，无限购文件 | B.conditionErrorBlocksBothChainsAndQuotaAndSurvivesRestart（6 种失败） |
| ERROR 正常回执与重启 | conditionError 有 code/path，重启不产生假未完成交易 | B.conditionErrorBlocksBothChainsAndQuotaAndSurvivesRestart |
| ERROR 回执写失败 | 不执行业务，继续阻断，重启保留 pending | B.failedConditionErrorReceiptKeepsDurableGateAcrossRestart |
| 显示失败发生在提交后 | 不发未解析消息、不宣称回滚、余额保持提交结果 | A.failedPapiAfterCommitDoesNotPretendDebitWasRolledBack、A.checkedMessageBoundaryPreservesCommitAndDoesNotLeakProviderData（5 种失败） |
| 日志定位、去重和脱敏 | 一次解析失败 WARN，含安全位置/代码，无输入、结果或异常原文 | P.productionProviderFailureWarnsOnceWithSafeConditionLocation、C.providerFailuresCannotPassInequalityNegationOrNestedGroups、A.checkedMessageBoundaryPreservesCommitAndDoesNotLeakProviderData |
| 非主线程调用、非法参数 | 先失败，不访问插件/解析后端 | P.workerParsingDoesNotInvokeBackend、P.nullArgumentsFailBeforeBackendAndResultRepresentationIsRedacted |

## 全部占位符调用点

- `PlaceholderApiResolver.Bridge`：唯一直接 setPlaceholders 调用，延迟链接可选插件。
- `ConditionEngine.resolve`：placeholder 叶，以及 numeric/string 的 placeholder 来源；同一受检入口。
- `TransactionalActionEngine` 的 message：经 ActionMessageTemplate 识别 `%无空白标识%` 后使用受检入口。原始普通显示文字中的孤立 `%` 不作为 PAPI 变量；这与条件解析结果的严格残留 `%` 校验是不同层次。
- `ActionMessageTemplate` 的 `$(...)`：既有数值输入/算式替换，非 PAPI；validate 的临时字符串 `0` 只用于纯语法遍历，未进入运行时失败回退。
- 标题、正文、输入标签、按钮 label/tooltip 的 TextRenderer 当前不调用 PAPI；按原 TextSpec 渲染，不增设隐式解析。
- 命令参数的输入引用及 `{player}`/`{uuid}` 沿用既有契约，不引入 PAPI 或权限允许列表。
- 插件 getter/构造注入只传递解析器，不提前解析、不访问外部扩展。

## 日志与剩余限制

- PAPI 已记录失败时，Result.warningLogged 阻止 ConditionEngine 再次告警。旧适配器的未记录失败由条件/消息消费端记录。正常 FAIL、未访问分支和成功解析无失败 WARN。
- 交易失败的最终状态 WARN 是独立的交易结果记录，不是第二次解析或条件告警。Result.diagnosticCode 与 toString 不包含外部错误值。位置仅来自 transaction UUID、固定路径和动作下标。
- 保留既有严格规则：PAPI **返回结果**中含任何 `%` 都失败，包括合法字面 `50%`；空输出若确实由扩展返回则仍可用于字符串比较，但数值比较失败。没有猜测扩展缺失时应有的默认值。
- 扩展返回“可解析但不正确”的数值无法由通用解析层识别；条件检查也不是 Vault 扣款的原子余额保证，实际扣款仍以生产经济适配器结果为准。
- 第三方扩展在主线程自行阻塞、内部自行记录敏感日志不受本插件的日志过滤控制。本轮未改变线程模型或增加异步 Bukkit/PAPI 调用。
- 本轮自动测试使用注入后端和 MockBukkit，未对原服务器真实 PAPI 扩展、Vault 或客户端执行交易。没有自动修改扩展、密码、真实菜单或玩家文件。
- 新 conditionError 回执由当前版本识别；降级到旧版本会保守地把它视为未决记录。旧版已经写成 denied 的记录缺少依据，不猜测并改写历史。

## 验证与复现

执行 `./gradlew.bat build --offline --no-daemon --console=plain`，Java 25。构建后执行 `python scripts/verify-placeholder-audit.py`，结果与 JAR 摘要写入 `build-reports/placeholder-audit-results.json`。当前完整结果由该报告及 PROJECT_CONTEXT.md 记录。

本轮完整 build：476 项测试，473 通过、3 项既有原生 Provider 跳过、0 失败；其中 173 项相关测试全部通过，较阶段 7.4 新增 36 项用例。报告统计参数化用例按 Gradle XML 套件总数计算；覆盖表按源码测试方法定位，不把参数化 XML 显示名称误当成方法名。最新导出为 dist/DialogCanvas-0.1.0.jar，与构建 JAR SHA-256 一致；1111 个前端文件逐字节一致，未打入 me/clip/placeholderapi 类。
