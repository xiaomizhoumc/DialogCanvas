# 步骤 3.6：占位符解析层

## 入口与可选依赖

消费入口为 `PlaceholderResolver.resolveResult(Player, String, String location)`，兼容已有 `resolveResult(Player, String)`，生产实现为 `PlaceholderApiResolver`。原来的 Optional resolve() 适配器仍可使用，默认 resolveResult() 将其输出转换为结构化结果并检查残留百分号。

PlaceholderAPI 固定编译依赖为 2.12.3，使用 compileOnly，不打入单 JAR；paper-plugin.yml 声明 required=false、load=BEFORE、join-classpath=true。单独的延迟加载 Bridge 调用 PlaceholderAPI.setPlaceholders(Player, String)。未安装插件不会导致 DialogCanvas 不能加载。

官方依据：[PlaceholderAPI 接入文档](https://wiki.placeholderapi.com/developers/using-placeholderapi/)、[2.12.3 发布记录](https://github.com/PlaceholderAPI/PlaceholderAPI/releases/tag/2.12.3)、[Paper 插件依赖文档](https://docs.papermc.io/paper/dev/getting-started/paper-plugins/)。

插件主类创建解析器并提供 getPlaceholderResolver()。当前已接入生产条件和消息动作；最新错误策略、分支验证和边界见 PLACEHOLDER_FAILURE_AUDIT.md。未部署本轮 JAR 到原服务器。

## Result

- success=true：value 是解析结果，error=null；空字符串可以是合法扩展输出。
- success=false：value=null，error 是失败原因。
- warningLogged 是内部日志状态，用来避免条件引擎再次记录同一次失败。
- 无百分号的普通文本无需 PlaceholderAPI，原样返回成功。
- 有占位符时检查插件是否启用；缺失/禁用、后端异常、类链接失败、返回 null 均返回失败。
- 解析输出中只要仍包含 `%` 就拒绝并记录 WARN，包括完整未替换占位符、部分未替换、孤立百分号以及字面值 `50%`。这是本轮要求的严格残留百分号规则，没有自动转义。
- 日志仅记录错误码与条件/动作位置，不写入玩家输入、解析结果或异常原文。Result.diagnosticCode()/toString() 提供脱敏诊断。

## 线程与条件求值

生产解析必须在 Scheduler.sync 主线程进行；工作线程调用返回 NOT_MAIN_THREAD，不调用 Bukkit 插件查询或 PAPI 后端。解析器不另行调度，确保条件引擎一次求值保持同一主线程上下文。

ConditionEngine 使用 resolveResult()，拒绝失败结果；将其视为 PLACEHOLDER_RESOLUTION_FAILED / ERROR，默认不通过，negate 和 OR 都不能将已访问的解析错误变为通过。成功结果再次检查残留百分号，防止自定义解析器跳过校验。同次求值内依旧缓存同一占位符。

生产解析器在失败时记录一次 WARN；条件引擎消费已记录失败时不重复 WARN。旧 Optional 适配器失败由条件引擎记录 WARN。

## 验证

13 个 MockBukkit 解析层测试通过；39 个既有条件引擎测试通过。覆盖插件缺失、普通文本、完整解析、合法空值、完整/部分未替换、字面百分号、后端异常、链接失败、工作线程拒绝、negate/OR 失败关闭、日志去重及单次求值缓存。

完整 build 成功：160 项测试，157 项通过，3 项既有 Dialog Provider 限制而跳过。已检查生成 JAR 不包含 me/clip 的 PlaceholderAPI 类。本轮使用注入的测试后端，没有在真实服务器安装 PlaceholderAPI 或扩展进行验证。

## 阶段 7.5 更新

条件失败保留 ERROR，访问到的错误不能被 !=、negate 或 OR 隐藏；不执行成功或拒绝动作，不扣款、不发货、不提交额度。生产回执使用 conditionError 与 code/path；回执落盘失败仍保留未决阻止。正常 FAIL 才使用 denyActions。消息解析处于提交后，失败为 POST_COMMIT_FAILED，不能宣称已回滚。完整当前验证见 PLACEHOLDER_FAILURE_AUDIT.md 与 build-reports/placeholder-audit-results.json；上文步骤 3.6 数量为历史记录。
