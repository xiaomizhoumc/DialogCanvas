# 阶段 10.2：统一错误反馈

日期：2026-10-16。保留 API `{ error, code, details? }`、HTTP 状态码、条件 PASS/FAIL/ERROR、交易/限购提交、补偿、阻断及恢复记录语义；没有增加自动重试或重放业务。

## 统一文案来源

唯一文案源：`frontend/src/i18n/errorMessages.json`，共 52 条，每个诊断码包含 message（管理端说明）、advice（建议操作）、player（简短玩家提示）。前端直接导入，Gradle processResources 将同一文件打入 `feedback/errorMessages.json`，Java ErrorFeedback 从 classpath 读取。文案不是运行时服主配置，不含菜单、凭据或交易参数，不需要网络或额外服务。

- 前端及控制台显示诊断码；交易诊断还带实际 transaction UUID 作为操作编号，便于核查原有交易记录。Web API 沿用现有响应，不凭空生成无法关联的操作编号。
- 玩家侧只显示简短结果；服务端记录诊断码/事务编号。菜单展示和管理分发错误也不再附第三方 Throwable 或异常原文。
- 未知诊断码使用安全的通用提示，保留符合格式的诊断码，不回显顶层 error 原文、网络异常、堆栈或绝对路径。字段 details 仍解释服务端受控的具体校验原因，不当作 HTML 渲染。
- 审计日志仍采用原有实际结果与固定字段，不改变账本，不把显示提示当作业务提交依据。事务结果由现有状态和持久记录决定，不用异常字符串猜测是否回滚。

## 菜单与认证提示

| code / 场景 | 提示含义 | 建议操作 |
| --- | --- | --- |
| VALIDATION_FAILED（422） | 配置未通过，显示正文/输入/按钮及动作次序、字段和具体原因；现有 JSON Pointer 继续标记属性面板 | 修正对应字段后校验，不保存非法文档 |
| UNSUPPORTED_CAPABILITY（details） | 保留服务端的具体限制，例如服务器维度限购尚未实现，而非笼统称格式错误 | 按具体限制调整，保留原始配置以供核查 |
| REVISION_CONFLICT（409） | 其他标签页或重载已修改正式菜单，保留当前草稿 | 先复制需要保留的修改，再重新读取并合并 |
| YAML_SYNC_CONFLICT（409） | 手动 YAML 有待导入修改或基线无法确认 | 核查 JSON/YAML/基线，确认后控制台 reload；不反复保存/删除，不删基线强制覆盖 |
| MENU_REFERENCED（409） | 其他菜单仍有引用 | 沿用引用弹窗、引用菜单入口和 details 路径；修改引用并保存后再删除，无强制/级联删除 |
| YAML_SYNC_FAILED（成功响应 warnings） | **正式 JSON 与注册表已提交，但 YAML 导出失败** | 保持新 revision/基线和当前草稿，检查文件权限/同步状态，勿重复提交本次保存 |
| REGISTRY_PUBLISH_FAILED / STORAGE_WRITE_FAILED / STORAGE_DELETE_FAILED | 按已有契约分别说明已恢复或未发布，不把这些有保证的结果一律称为未知提交 | 草稿保留，检查发布、空间、权限或手动文件 |
| MENU_STATE_UNCERTAIN（503） | 补偿无法确认，服务已阻断 | 勿重试；服主停服核查正式文件、YAML 与同步记录 |
| AUTH_REQUIRED（401） | 显示登录入口，保留当前页内存文档、撤销和草稿 | 重新登录；不自动读取覆盖，不自动提交 |
| ORIGIN_INVALID / CSRF_INVALID | 来源/校验不符合要求 | 使用真实本机地址或重新登录；不放宽 Origin/CSRF |
| 网络中断 / REQUEST_TIMEOUT / INVALID_RESPONSE | 按读写性质区分“读取失败”和“写入结果无法确认” | 保存草稿，服务恢复后读取核查；不盲目重试写请求 |

手写配置的校验原因保留；更新了已过时的“高级源码”入口提示和分拆命令输入说明，现在按完整命令编辑方式说明，同时保留底层 command/arguments 配置契约。

## 条件、动作与生产交易

| 实际状态 | 玩家反馈原则 | 管理端诊断与保持的业务语义 |
| --- | --- | --- |
| 条件 FAIL | 没有配置 denyActions 时提示“未满足使用条件”；有 denyActions 时沿用服主配置，避免多发一条普通拒绝消息 | 不记录正常不满足 WARN；拒绝链仍按原规则执行，成功动作不执行 |
| 条件 ERROR | “条件无法读取，本次未扣款，请联系服主” | 原 ConditionEngine/PlaceholderResolver WARN 去重、定位、脱敏保持；不执行成功或拒绝链、不预留限购、不扣款发货；原 conditionError 收据保持 |
| REJECTED | 操作未执行，不称“已回滚” | TRANSACTION_REJECTED；不改变原 Outcome/收据映射 |
| ROLLED_BACK 且没有不确定状态 | 操作失败，已回滚可撤销的数据修改；余额不足使用简短专用提示 | TRANSACTION_ROLLED_BACK / INSUFFICIENT_FUNDS；仅在引擎确认补偿完成时使用 |
| RECOVERY_REQUIRED 或 ProductionStore/限购提示不确定 | 交易结果待核查，勿重复购买 | RECOVERY_REQUIRED；覆盖看似回滚的局部状态，保留阻断与恢复证据 |
| RECOVERY_SAVE_FAILED | 需要人工核查，勿重复购买 | 明确补偿未完成且恢复记录未完整保存，不隐去该区别，不声称已回滚 |
| POST_COMMIT_FAILED | 操作已提交，后续动作失败，勿重复点击 | 数据/限购提交规则保持；不声称扣款或发货已撤销，不自动重放消息/命令 |
| 最终收据或生产收尾失败 | 最终结果待核查 | PRODUCTION_OPERATION_FAILED + 已有事务编号；不将异常直接解释为回滚，保留原 Future 失败及阻断 |

修复原拒绝动作链只转换收据状态、不报告动作失败的路径：现在与成功动作链共用结果解释。默认拒绝提示不改变状态；拒绝链的副作用失败仍按原规则归类 POST_COMMIT_FAILED，正式收据仍保持原 committed 语义，不新增交易状态或账本。

事务反馈均在已有主线程入口运行；未包裹重复调度，不新增文件 I/O、BCrypt 或主线程等待。原 player/op/console 与临时 OP finally 恢复保持，无命令允许列表。

## 请求、旧响应与草稿

- 客户端整个 fetch + 响应正文读取设 **30 秒超时**，使用 AbortController 和超时 Promise；断开或超时均不自动重试。客户端取消等待不表示服务端取消提交。
- 写请求成功响应正文中断、非法 JSON、null/数组/标量等无效成功正文会标为结果不确定。204 正常无正文。错误 details 类型不合法时过滤，避免反馈面板崩溃。
- validate 虽为带 CSRF 的 POST，但不持久化；超时不误称正式数据可能写入。确认未提交/已补偿的有限 API 错误码沿用原契约，其余未知 5xx 写结果保守视为不确定。
- 正式保存后，YAML 警告先处理；即使列表刷新失败或返回 401，也不会丢掉部分成功说明。后到的列表错误不作用于另一份菜单；旧保存校验不把字段错误标到已经编辑过的新文档。
- 删除成功响应到达时，如等待期间已有新文档修改或临时输入，保留当前页草稿并明确“正式菜单已删除”；不再自动清空这些修改。此时请复制所需内容后新建，不盲目再次保存旧 revision。
- 草稿只在页面内存，刷新、关闭或崩溃后不保证恢复。重新读取会经过既有未保存确认；不是自动合并或持久化草稿。

## 本轮确认的问题及修复范围

1. 原顶层 API/网络错误直接显示 error.message，缺少分码中文操作建议：改用共用文案，具体字段详情保留。
2. 原请求没有超时，response.text() 失败在网络 catch 外，可能变成无法判断提交状态的普通异常：补齐超时、正文中断、无效成功正文处理。
3. 原条件 FAIL 的拒绝链失败没有进入公共结果反馈：复用统一解释，仅增加反馈。
4. 原 YAML 警告处理在刷新列表之后，列表失败时可能丢掉部分成功提示：提前判定并组合展示。
5. 原删除请求等待期间的新编辑可能被成功回调清空：新增当前文档与临时层比较，保留新草稿。
6. 菜单展示/管理分发的未知异常会附原文或堆栈：改为固定诊断；管理结果审计与异常传播保持。

没有更改条件 ERROR 语义、事务执行顺序、限购、持久化、认证或 API 路由。

## 验证与复现

汇总：`build-reports/error-feedback-results.json`；浏览器明细 `error-feedback-browser-results.json`；隔离 Paper 明细 `stage10-2-paper-results.json`。

- TypeScript、前端构建与完整 Gradle build 成功。
- 前端 170 项全部通过：新增 14 项，覆盖分码/脱敏、部分成功、字段原因、超时/正文断开/非法成功正文/无重试等。
- Java 603 项：599 通过、4 项既有 MockBukkit 能力跳过、0 失败/错误。新增 7 项：反馈纯函数 4、生产拒绝分支 2、管理分发脱敏 1；原补偿、恢复写失败、最终收据失败、占位符 ERROR、临时 OP、权限和同步冲突回归。
- 独立 Edge + 当前生产 dist + 模拟 REST：14 项通过，无脚本异常；截图已检查。覆盖登录失效/旧读取、revision/YAML、字段、未知敏感原文、离线、部分成功加列表失败、删除期间新编辑。
- 最新同一 JAR 的隔离 Paper Build 74：13 项，验证实际 HTTP 错误契约、认证/Origin/CSRF、JSON/YAML、并发 revision、待导入保存/删除保护、手动重载、退出/重启失效及端口释放。没有连接 Minecraft 客户端。

工作区导出 `dist/DialogCanvas-0.1.0.jar`，11,428,418 字节，SHA-256：`84d8c138ca7f3abaed1930d8f4fc6bb3c2fcd26f37ea7b37eeca9674e6ba86e3`。1050 个前端资源和共用文案与 JAR 逐字节匹配；未部署原服务器。

```powershell
# frontend 目录
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=../build-reports/error-feedback-frontend-results.json
# 项目根目录（沿用 Java 25 / 既有 Gradle 缓存）
./gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
node scripts/verify-error-feedback.mjs
python scripts/verify-stage7-paper.py --stage10-2
python scripts/verify-error-feedback.py
```

浏览器离线使用 CDP 网络模拟。初次夹具用服务端直接断连接，受到 Chromium 底层连接重传干扰，未算通过；改为 CDP 离线后完整重跑。应用层无自动写重试由单元测试及最终浏览器行为验证；不承诺控制浏览器或代理内部的所有传输重试。首次文案测试把“不能宣称已回滚”误匹配为回滚提示，改为更清楚的“无法确认补偿完成”后完整回归通过。

## 待人工检查

本轮不部署原服务器、不操作真实玩家数据。实际客户端的提示长度、颜色/字体、真实 Vault/PAPI 失败、第三方命令自己的反馈需服主后续验证。普通生产购买仍以原交易保护为准；不做真实扣款发货试验、不自动重放失败交易。保留第三方自己的日志、硬断电、外部编辑器并发等既有限制。构建中的大包体积及 ByteBuddy 提示保持，不计为失败。
