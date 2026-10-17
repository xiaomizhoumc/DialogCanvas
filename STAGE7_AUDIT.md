# 阶段 7：P0 修复汇总与联合验收

日期：2026-10-15。阶段：7.6。固定 Paper 26.1.2 Build 74、Java 25、现有锁定依赖。

## 结论与分级

五项审计均有当前代码及回归覆盖。确认的 **4 类 P0 已修复并验证，无未解决的确认 P0**；本阶段在下列单实例、目录所有权与正常停服约束内完成。此结论不是对任意外部程序并发写、断电、第三方插件或未连接客户端的无限保证。没有证据的事故/攻击结果单独列为待核实。

- P0：已证实的路径可能静默覆盖正式/手动数据或原始恢复依据，或让必要交易/计数持久化与成功副作用失配，必须阻断发布。
- P1：已确认的线程、失败清理、权限入口/会话撤销、诊断泄漏路径和结果错误，可能使操作失败或削弱保护；不据此推断已经发生真实资金损失、凭据泄漏或认证绕过。
- P2：资源/可用性、误互斥和交互语义问题。分级按影响及前提，不把所有审计项都改称 P0。
- 待核实：只有理论窗口、缺少实际后果证据或测试范围之外的情形，不写成已发生/已修复事故。

本轮新增 9 项联合测试及验收工具/文档，没有新增生产功能，没有重建认证、存储或线程模型。前五步源码修复沿用当前实现；联合验收未发现需再修改生产代码的确认 P0。下表行号由当前源码重新定位，历史分步报告中的旧行号仅供历史对照。

## 五项审计的覆盖依据

| 步骤 | 记录 | 当前验证 |
|---|---|---|
| 7.1 线程安全 | THREAD_SAFETY_AUDIT.md；历史 thread-audit-artifact.json / thread-lifecycle-results.json | Scheduler/命令/显示/生产/限购回归；联合 worker 收据；最新独立 Paper 两次正常停止 |
| 7.2 文件与持久化 | PERSISTENCE_AUDIT.md；历史 persistence-audit-results.json | 原子移动拒绝、手动 YAML/JSON 补偿、迁移、限购/收据故障与重启；最新 Paper 的 JSON/YAML 冲突 |
| 7.3 认证与敏感信息 | AUTH_SECURITY_AUDIT.md；历史 auth-audit-results.json | 全 API/Origin/CSRF/Cookie/改密/过期/重启；最新 Vite 4 项及独立 Paper HTTP 检查 |
| 7.4 点击入口与去重 | CLICK_ENTRY_AUDIT.md；历史 click-entry-audit-results.json | 回调角色/身份/会话/版本/次数/到期；异步重复点击、最终回执挂起及停止后迟到收据 |
| 7.5 占位符失败 | PLACEHOLDER_FAILURE_AUDIT.md；历史 placeholder-audit-results.json | ERROR/FAIL、!=/negate、AND/OR 短路、异常/null/残留/非法数值、日志及生产阻断 |

历史报告保留当时的 JAR 摘要和测试数量，不把它们当成最新 JAR 的实测，也不把历次数量相加。最新结果统一为 build-reports/stage7-audit-results.json 和 stage7-paper-results.json；后者的实例 JAR 摘要必须与最终产物一致。

## 确认问题逐项汇总

### P0：数据与交易完整性

| 项目 / 实际影响与证据 | 当前问题位置 | 修复 | 验证 | 剩余风险 |
|---|---|---|---|---|
| S7-P0-01 / 数据损坏 / 步骤 7.2：手动 YAML 在删除补偿或导出准备期间被新改，旧路径仍可能替换旧内容。故障注入可确认覆盖路径。 | `plugin/src/main/java/dialogcanvas/web/menu/MenuRepository.java:310`<br>`plugin/src/main/java/dialogcanvas/storage/AtomicFiles.java:34` | 补偿只恢复缺失文件；导出/分离前复核预期字节，冲突保留新 YAML。 | `MenuSynchronizationTest.manualEditDuringDeleteCancelsDeleteAndCompensationPreservesNewYaml`<br>`MenuSynchronizationTest.publicationFailureAfterDeleteDoesNotOverwriteNewManualFile`<br>`AtomicFilesTest.changedManualDocumentIsNotReplacedAndTempIsCleaned`；通过 | 最后检查与 rename 不是跨进程 CAS；与不协作外部编辑器同时写同一文件仍属 R1 限制。 |
| S7-P0-02 / 数据损坏 / 步骤 7.2：注册表发布失败后，无条件补偿正式 JSON 会覆盖期间外部新文件。 | `plugin/src/main/java/dialogcanvas/web/menu/MenuRepository.java:318` | 只补偿仍匹配本次提交的文件；不匹配/补偿失败保留文件，503 并停用菜单服务。 | `MenuRepositoryTest.publicationCompensationCannotOverwriteExternallyChangedFormalJson`<br>`MenuRepositoryTest.failedCompensationMakesServiceUnavailableRatherThanReportingSuccess`；通过 | 不支持运行中手改正式 JSON；一致性不明时需人工核查，不能强行继续保存。 |
| S7-P0-03 / 恢复依据丢失 / 步骤 7.2：迁移备份已写、正式提交未完成时，重试可能用新编辑源覆盖原始备份。 | `plugin/src/main/java/dialogcanvas/web/menu/MenuRepository.java:92` | 备份只创建一次，核对源与备份摘要/回执；冲突停止，不复活已删除菜单。 | `MenuSynchronizationTest.interruptedMigrationCannotReplaceOriginalBackupAfterLegacyEdit`<br>`MenuSynchronizationTest.receiptWithoutFormalJsonDoesNotResurrectDeletedMigratedMenu`；通过 | 多菜单迁移非整体原子；中断保留文件及回执，不能自动猜测正确版本。 |
| S7-P0-04 / 交易与计数不一致、重试风险 / 步骤 7.2：成功副作用先于限购/冷却持久化；文件失败可能留下业务已发生但必要状态未确认。 | `plugin/src/main/java/dialogcanvas/engine/production/ProductionActionEngine.java:101`<br>`plugin/src/main/java/dialogcanvas/engine/limit/LimitService.java:73`<br>`plugin/src/main/java/dialogcanvas/engine/production/ProductionStore.java:73` | 先限购提交，再 effectsPending，再副作用，最后最终收据；失败保留预留或交易阻止，不自动重试。恢复记录共用 transactionId。 | `ProductionActionEngineTest.quotaAndJournalAreDurableBeforeCommandAndSuccessMessage`<br>`ProductionActionEngineTest.failedDurableCheckpointDoesNotSendSuccessOrDispatchCommandAndBlocksRestart`<br>`ProductionActionEngineTest.finalReceiptFailurePreservesEffectsPendingEvidenceAndDoesNotAllowRetry`<br>`LimitServiceTest.failedDurableQuotaCommitRetainsReservationAndNeverRunsSuccessEffects`；通过 | 跨 Vault/背包/多个文件不是 ACID；未知结果须人工核查，未承诺恰好一次或自动退款。 |

### P1：线程、失败处理与保护边界

| 项目 / 实际影响与证据 | 当前问题位置 | 修复 | 验证 | 剩余风险 |
|---|---|---|---|---|
| S7-P1-01 / 线程越界 / 步骤 7.1：Build 74 的异步命令树会调用 canUse，旧实现直接读取玩家权限。 | `plugin/src/main/java/dialogcanvas/command/ManagementCommand.java:42` | 根命令可见；真正执行权限仍在主线程检查，异步建议不读游戏状态。 | `ManagementCommandTest.asyncCommandTreeAndSuggestionsNeverReadPlayerPermissions`<br>`ManagementCommandTest.permissionAndStartupGateExecution`；通过 | 不能据此推断其他插件也遵守线程规则。 |
| S7-P1-02 / 挂起与清理失败 / 步骤 7.1：Scheduler 用户回调持登记锁，以及 disabled/调度拒绝路径，可能阻塞清理或留下未完成 Future。 | `plugin/src/main/java/dialogcanvas/scheduler/Scheduler.java:28`<br>`plugin/src/main/java/dialogcanvas/scheduler/Scheduler.java:72` | 用户代码/取消回调移出锁，关闭前允许立即主线程清理，排队/关闭后明确拒绝并结算。 | `SchedulerTest.inlineUserCodeDoesNotHoldRegistrationLock`<br>`SchedulerTest.disabledPluginAllowsImmediateCleanupButRejectsWorkerAndDelayedTasks`<br>`SchedulerTest.cancellationAndCloseSettlePendingFuturesWithoutExecutingGameWork`；通过 | 工作线程无法强制取消已经开始的第三方副作用。 |
| S7-P1-03 / 停服资源失败 / 步骤 7.1：隔离 Build 74 曾复现 onDisable 返回后 Jetty 收尾触发 zip file closed。 | `plugin/src/main/java/dialogcanvas/DialogEditorPlugin.java:251`<br>`plugin/src/main/java/dialogcanvas/web/EmbeddedHttpServer.java:239` | 先截止游戏操作，后台 I/O/Jetty drain；仅停服按已授权规则最多等待 30 秒。 | `EditorHostingTest（全套件）`<br>`Stage7JointAcceptanceTest.asynchronousClickCommitsAllStateBeforeCompletionAndRejectsConcurrentDuplicates`；通过 | 超时/强制终止不保证全部收尾；真实 Paper 两次正常 stop 通过，非硬断电实验。 |
| S7-P1-04 / 失败误报与未决交易 / 步骤 7.1：菜单显示异常曾转成正常完成；生产收尾依赖可拒绝调度，关闭可能过早关闭存储；离线背包回滚不能证明已保存。 | `plugin/src/main/java/dialogcanvas/dialog/SavedMenuOpener.java:33`<br>`plugin/src/main/java/dialogcanvas/engine/production/ProductionActionEngine.java:71`<br>`plugin/src/main/java/dialogcanvas/engine/TransactionalActionEngine.java:125` | 保留异常 Future；并发集合结算不再依赖 Bukkit 调度；先结算再关存储，离线/迟到结果不推进，必要时持久恢复记录。 | `SavedMenuOpenerTest（全套件）`<br>`ProductionActionEngineTest.shutdownDuringFileIntentSettlesEvenAfterSchedulerRejectsAndPreservesRecovery`<br>`Stage7JointAcceptanceTest（全套件）`；通过 | 离线/第三方后台写入的真实损失未在客户端复现；不把持久恢复记录等同已补偿。 |
| S7-P1-05 / 路径越界与不可读状态 / 步骤 7.2：junction/符号链接可绕过简单目录判断；超大生产 JSON 可能写得出、重启读不了。 | `plugin/src/main/java/dialogcanvas/storage/AtomicFiles.java:82`<br>`plugin/src/main/java/dialogcanvas/engine/production/ProductionStore.java:147` | 统一真实路径/目标类型检查；生产玩家/交易/恢复与限购读写同为 4 MiB 上限，写失败不发布新缓存。 | `AtomicFilesTest.redirectedAncestorIsRejectedBeforeCreatingFilesOrDirectories`<br>`ProductionStorePersistenceTest.failedPlayerWriteKeepsFileAndCacheAndOversizeCannotBecomeUnreadableState`<br>`ProductionStorePersistenceTest.redirectedRootDoesNotCreateBusinessDirectoriesOutsideFixtureRoot`；通过 | 目录由单实例持有，不承诺抵抗拥有系统权限且持续重定向目录的程序。 |
| S7-P1-06 / 敏感诊断暴露 / 步骤 7.3：配置 record 文本表示含哈希；YAML 异常链可能携带配置原文。已复现输出路径，未发现服主真实泄漏证据。 | `plugin/src/main/java/dialogcanvas/config/PluginConfig.java:82`<br>`plugin/src/main/java/dialogcanvas/config/PluginConfig.java:93` | 隐藏哈希文本表示；语法异常改固定诊断，不挂原解析堆栈。 | `PluginConfigTest（全套件）`<br>`AuthServiceTest.bootstrapYamlFailureCannotLeakSourceAndNeverPrintsOrEnablesAuth`；通过 | 历史终端录像/系统备份不受插件清理控制，真实泄漏列 R2 待核实。 |
| S7-P1-07 / 密码比较别名 / 步骤 7.3：无效 UTF-16 转 UTF-8 被替换，固定 BCrypt 中可出现不同输入同验证结果。 | `plugin/src/main/java/dialogcanvas/web/auth/AuthService.java:203` | 前后端拒绝无效 Unicode，严格 UTF-8 72 字节边界；新密码按码点计数，不截断。 | `AuthServiceTest.isolatedSurrogatesMustNotAliasValidPasswordsOrBePersisted`<br>`AuthServiceTest.exactUtf8BoundaryAcceptsMultibyteAndSupplementaryCharactersWithoutTruncation`；通过 | 未复现通用认证绕过，未改服主密码；旧异常密码由服主核查。 |
| S7-P1-08 / 开发同源防线失效 / 步骤 7.3：开发代理改写 Origin 之前未校验原始来源；不是已证实的会话/CSRF 绕过。 | `frontend/dev/localApiGuard.ts:1`<br>`frontend/vite.config.ts:1` | 代理先核对本机 Host/Origin，关闭开发 CORS；生产认证、Cookie、CSRF 不放宽。 | `AuthHttpTest.writesRejectForeignOrMissingOriginAndInvalidCsrfWithoutLoggingOut`；通过 | 开发代理真实请求 4 项通过；不能据此宣称任意浏览器扩展安全。 |
| S7-P1-09 / 过时点击仍可发起业务 / 步骤 7.4：原回调仅查在线/UUID，没有最新界面、原 Player 对象、次数/到期或正式版本租约。 | `plugin/src/main/java/dialogcanvas/dialog/DialogRenderer.java:342`<br>`plugin/src/main/java/dialogcanvas/dialog/DialogRenderer.java:369`<br>`plugin/src/main/java/dialogcanvas/web/menu/MenuRepository.java:41` | 准入时主线程验证身份/最新 Session/Options/正式版本；保存、重载、删除重建及已知关闭撤销旧入口。 | `DialogCallbackAuditTest.rejectsWrongOwnerNonPlayerOfflineAndReconnectedObjectWithSameUuid`<br>`DialogCallbackAuditTest.versionLeaseRevocationBlocksQueuedClicksBeforeAdmission`<br>`MenuRepositoryTest.callbackLeaseRejectsDraftsUpdatesDeletionRecreationReloadAndClosedService`；通过 | 原生 Esc/外部关闭没有通知；当前屏幕状态及真实客户端旧包效果列 R3。已准入交易继续冻结快照。 |
| S7-P1-10 / 取消破坏串行与入口卡死 / 步骤 7.4：公开 Future 取消可能破坏内部完成信号；同步初始化异常可遗留玩家 active/pending。 | `plugin/src/main/java/dialogcanvas/engine/TransactionalActionEngine.java:74`<br>`plugin/src/main/java/dialogcanvas/engine/production/ProductionActionEngine.java:85` | 对外返回副本，内部信号决定真实结算；同步异常清理玩家门，锁覆盖最终回执，不因取消提前放开。 | `ProductionActionEngineTest.cancellationAndNewGuardCannotBypassPlayerWideAdmissionWhileOtherPlayerCanBuy`<br>`Stage7JointAcceptanceTest.asynchronousClickCommitsAllStateBeforeCompletionAndRejectsConcurrentDuplicates`；通过 | 可信服务端 API Future 与任意客户端网络入口不同；无持久请求 ID，不承诺永久判重。 |
| S7-P1-11 / 错误状态混淆 / 步骤 7.5：生产条件 ERROR 已阻断业务，但回执仍标普通 denied，缺少错误位置。 | `plugin/src/main/java/dialogcanvas/engine/production/ProductionActionEngine.java:97`<br>`plugin/src/main/java/dialogcanvas/engine/production/ProductionStore.java:19` | 使用 conditionError 终态与 code/path；成功/拒绝链都不执行。写回执失败仍持久阻断。 | `ProductionActionEngineTest.failedConditionErrorReceiptKeepsDurableGateAcrossRestart`<br>`Stage7JointAcceptanceTest（全套件）`；通过 | 旧 denied 无依据不改写；旧 JAR 不识别新终态时保守阻断。 |
| S7-P1-12 / 自定义解析错误与日志泄漏路径 / 步骤 7.5：自定义 Result 可返回 null/未解析成功值/LinkageError；错误原文可进入日志，消息消费边界检查不足。 | `plugin/src/main/java/dialogcanvas/engine/condition/PlaceholderResolver.java:23`<br>`plugin/src/main/java/dialogcanvas/engine/condition/PlaceholderResolver.java:48`<br>`plugin/src/main/java/dialogcanvas/engine/TransactionalActionEngine.java:380` | 受检边界与安全诊断码；解析失败 WARN 一次，日志带路径不带输入/结果。条件 ERROR 不取反，消息失败保留 POST_COMMIT_FAILED。 | `ConditionEngineTest.runtimeErrorAbortsOrBeforeAnOtherwisePassingBranch`<br>`PlaceholderApiResolverTest.conditionFailsClosedUnderNegationAndOrWithoutDuplicateWarn`<br>`TransactionalActionEngineTest.failedPapiAfterCommitDoesNotPretendDebitWasRolledBack`；通过 | 残留任何 % 仍拒绝；合法但错误的扩展数值无法通用识别，真实扩展行为待客户端核验。 |

### P2：资源与功能问题

| 项目 / 实际影响与证据 | 当前问题位置 | 修复 | 验证 | 剩余风险 |
|---|---|---|---|---|
| S7-P2-01 / 主线程卡顿 / 步骤 7.1：启动配置 I/O/图标资源读取曾位于主线程。 | `plugin/src/main/java/dialogcanvas/DialogEditorPlugin.java:47`<br>`plugin/src/main/java/dialogcanvas/config/PluginConfig.java:35` | 配置与资源预载移工作线程，游戏能力捕获与注册仍在主线程。 | `PluginConfigTest（全套件）`<br>`ManagementCommandTest.playerAccessFromAsyncExecutionRunsOnMainThread`；通过 | 第三方插件自己的同步 I/O 不在本插件控制内。 |
| S7-P2-02 / 认证资源占用 / 步骤 7.3：异常 cost 可能长期占工作池；取消登录后可能留下无法交付的有效会话。 | `plugin/src/main/java/dialogcanvas/config/PluginConfig.java:67`<br>`plugin/src/main/java/dialogcanvas/web/auth/AuthService.java:244` | 启动限制格式/cost 4–16，生产生成 12；有界队列/会话数量，交付失败删除新会话。 | `AuthServiceTest.invalidConfiguredHashCannotMakeAuthReadyAndNeverChangesConfig`<br>`AuthServiceTest.canceledInFlightLoginCannotLeaveAnUndeliveredSession`；通过 | 旧低 cost 保留兼容，不自动改密；在途 BCrypt 不保证立即中断。 |
| S7-P2-03 / 误互斥与关闭语义 / 步骤 7.4：原键 UUID+buttonId 导致跨菜单同名按钮碰撞；已知 UI 关闭需明确撤销回调。 | `plugin/src/main/java/dialogcanvas/engine/ClickGuard.java:12`<br>`plugin/src/main/java/dialogcanvas/dialog/DialogRenderer.java:352` | 结构化 UUID/menu/button；关闭通过既有适配器撤销会话，纯关闭不调用业务链。 | `DialogCallbackAuditTest.differentMenusAndPlayersDoNotCollideInClickGuard`<br>`DialogCallbackAuditTest.closeAndNoneNeverEnterBusinessEngineAndCloseInvalidatesSession`；通过 | 不同按钮由生产玩家级门保护；UI 撤销不取消已准入交易。 |

## 联合调用顺序与实际断言

联合用例：`plugin/src/test/java/dialogcanvas/engine/production/Stage7JointAcceptanceTest.java`，真实临时文件 + MockBukkit + 注入经济回执/PAPI 后端。没有依赖被跳过的原生 Dialog Provider。

```text
工作线程点击 → ClickGuard 获取所有权
 → Scheduler 主线程准入/在线/生产玩家门/输入检查
 → 文件线程原子 pending 意图
 → 主线程条件检查（ERROR 在此结束，不执行 denyActions）
 → 文件线程限购/冷却检查与预留
 → 主线程扣款请求 → 工作线程完成经济回执 → Scheduler 恢复推进
 → 主线程发货 → 文件线程玩家业务数据持久化并发布缓存
 → 文件线程限购计数/冷却提交 → effectsPending 持久化
 → 主线程提交后命令/消息（临时 OP finally 恢复）
 → 文件线程最终收据 → 生产门释放 → ClickGuard finally 释放
 → 完成回调（线程不作继承假设，测试回调只读纯数据/文件）
```

生产条件之前还保留菜单准入和命令/快捷入口的权限要求；菜单 permission 条件由条件引擎执行。底层 LimitService 的权限→条件→限购→冷却顺序亦由既有 gateOrderAndMainThread 回归覆盖。没有新增命令允许列表，player/op/console 仍直接交原版/目标插件判断权限。

| 联合场景 | 检查与结果 |
|---|---|
| 成功 + 重复点击 | 工作线程发起；回执未到时连续 20 次重复仅一笔扣款/意图/预留。最终收据故意挂起时，业务已发生但锁和完成回调仍未结束；落盘后才完成。逐阶段顺序与主/工作线程断言通过 |
| PAPI 失败 | pending→ERROR→conditionError；成功和拒绝链均未调用经济，无限购文件，正常错误回执后不保留假未决门 |
| 初始意图写失败 | 扣款/发货/预留均为零，异常向调用者传播；去重锁释放，无错误持久阻止 |
| 玩家数据写失败 | 已执行扣款/发货逆序补偿，余额恢复、额度释放、无成功副作用，最终 rolledBack |
| effectsPending 写失败 | 状态与额度已提交，但命令/成功消息未执行；回执为 recoveryRequired，重启仍阻止交易 |
| 最终收据写失败 | 命令/消息可能已发生；保留 effectsPending 和玩家阻止，不能把调用异常当成“未扣款”重试 |
| 玩家离线 | provider 未结束时不释放锁；回执到达后主线程补偿，本例未发货且可完整回滚，额度不消耗；既有离线背包变更用例另行保留 recoveryRequired |
| 插件停止 | 截止交易，保留 recoveryRequired；工作线程迟到收据不能恢复发货/命令，重启阻止仍有效 |
| OP 命令抛错 | 状态已提交，结果为提交后副作用失败；OP 恢复，不发后续成功消息，不伪称回滚 |

联合测试之外，LimitService 故障注入验证预留写失败不执行业务、额度提交失败不执行成功副作用；生产套件验证退款/恢复记录写失败、不同玩家、调用方取消和多个规则。临时 OP 原状态为 true/false、成功/异常及 player/op/console 身份由既有事务套件覆盖，不只检查一次成功命令。

## 验收结果与验证层次

- 完整 Gradle build：成功。Java 485 项，**482 通过、3 跳过、0 失败/错误**。新增联合 9 项全部通过。
- TypeScript 检查：通过；Vite 生产构建：通过。前端 134 项，134 通过、0 失败/跳过；真实临时 Vite 开发代理 4 项通过。Vite 的现有大包体积提示不是构建失败，本轮未展开拆包重构。
- 独立真实 Paper Build 74：**13 项通过**；新建工作区实例，只含最新 DCMenu JAR，无客户端/Bot、无真实 Vault/PAPI 交易。验证操作来自 HTTP/控制台，不是原版客户端点按钮。
- 单 JAR：1111 个前端资源与 frontend/dist 逐字节一致；未打入可选 PAPI 或测试类，默认配置无密码哈希。最新 dist/DialogCanvas-0.1.0.jar 与 plugin/build/libs 及隔离 Paper 实例摘要一致。SHA-256：`b2722ea6c0f5a6fc4cf3685dc3611b8c981466d2f91f1d162332d1cad33f4686`。
- 原服务器：没有更新 JAR、没有修改/清理其数据、没有用服主凭据登录。本轮只读/写工作区代码、构建和全新隔离测试数据。

### 明确跳过

- `dialogcanvas.dialog.DialogRendererTest` / `[1] NOTICE`：既有 MockBukkit 原生 Dialog Provider 限制；**未通过，不计入通过数**。回调/业务保护另有不依赖该 Provider 的测试，但不代替原生渲染验证。
- `dialogcanvas.dialog.DialogRendererTest` / `[2] CONFIRMATION`：既有 MockBukkit 原生 Dialog Provider 限制；**未通过，不计入通过数**。回调/业务保护另有不依赖该 Provider 的测试，但不代替原生渲染验证。
- `dialogcanvas.dialog.DialogRendererTest` / `[3] MULTI_ACTION`：既有 MockBukkit 原生 Dialog Provider 限制；**未通过，不计入通过数**。回调/业务保护另有不依赖该 Provider 的测试，但不代替原生渲染验证。

### 最新隔离 Paper 检查清单

- PASS `latest_jar_console_address_and_packaged_root`
- PASS `actual_http_socket_loopback_only`
- PASS `unauthenticated_protection_login_cookie_and_session`
- PASS `authenticated_writes_preserve_origin_and_csrf_guards`
- PASS `validate_create_json_yaml_baseline_and_registry`
- PASS `invalid_model_preserves_formal_file_and_snapshot`
- PASS `same_revision_concurrency_has_one_commit_one_conflict`
- PASS `pending_yaml_blocks_save_and_delete_without_overwrite`
- PASS `console_yaml_import_publishes_json_and_new_snapshot`
- PASS `logout_immediate_session_invalidation`
- PASS `normal_stop_drains_resources_and_releases_port`
- PASS `restart_invalidates_token_keeps_json_revision_hash_config_and_pending_yaml`
- PASS `second_stop_clean_without_classloader_or_scheduler_failure`

## 待核实项、限制与阻塞规则

| 编号 | 状态与证据 | 剩余限制 / 处置 |
|---|---|---|
| R1 外部文件编辑竞争 | 最后内容检查与 rename 间的跨进程窗口存在；本轮没有复现该极短窗口中的静默覆盖，不宣称已经消除 | 当前验收以单实例持有目录、手动 YAML 与网页保存/删除错开为前提。不支持运行中手改正式 JSON/基线。若要求不协作外部编辑器任意并发下零覆盖，该更强要求**尚未满足，是该模式的阻塞条件**，需要协作锁/编辑协议，不以“文件原子移动”代替 CAS |
| R2 真实敏感信息泄漏或认证入侵 | 已修复可复现输出路径；7.3 有限扫描未发现服主凭据命中。本轮无新实际泄漏/绕过证据，标待核实 | 外部终端录像、重定向、浏览器/系统备份没有全部检查。仅有线索时由服主核查，不擅自改密码；不得共享 config 或测试浏览器目录 |
| R3 原版客户端/真实经济与扩展 | 本轮未连接客户端，未实际扣款发货、PAPI 扩展或第三方 OP 命令；不能把 MockBukkit 当实服效果 | 待授权部署后使用专用测试玩家核对余额、背包、计数、快速重复点击、离线/重开和命令身份。原生 Esc/外部关闭无通知，只能按会话/期限/版本等现有边界保护 |
| R4 崩溃、断电与第三方事务 | 未做硬断电/磁盘填满/强杀实验；已做异常注入和磁盘状态重建 | 无跨插件/多文件 ACID、无通用目录 fsync 保证。pending/effectsPending/recoveryRequired/限购预留跨重启阻止重试，人工核查，不能自动退款、发货或重放命令 |
| R5 生命周期与外部 provider | 最新正常 stop 两次通过；停止/离线/迟到 Future 已模拟 | 仅关闭期主线程最多等 30 秒；超时显式错误。无法强制取消第三方已开始的后台写入；第三方自己记录的敏感日志/阻塞不由本插件兜底 |
| R6 显示与兼容 | 固定严格百分号检测；旧 denied 不能推断来源，新 conditionError 旧版本不识别 | 合法字面百分号扩展输出仍拒绝，普通显示文字不因此强制依赖 PAPI。消息错误不撤销已经提交的交易。降级前须核对新回执，不自动改写历史 |

当前范围内没有未解决的确认 P0，因此本阶段自动与隔离服务验收通过。R1 的更强并发模式及 R3 客户端/真实交易不在通过声明内；若后续在这些范围确认 P0，必须重开阻塞、停止宣称相应场景完成，不能用本报告替代修复。

## 可复现命令

```powershell
# 前端目录；使用已有锁定依赖，不更新 lockfile
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=../build-reports/stage7-frontend-tests.json
node node_modules/vite/bin/vite.js build

# 项目根；JAVA_HOME 指向 Java 25，GRADLE_USER_HOME 使用既有离线缓存
./gradlew.bat :plugin:test --tests dialogcanvas.engine.production.Stage7JointAcceptanceTest --tests dialogcanvas.web.auth.AuthHttpTest --tests dialogcanvas.web.menu.MenuSynchronizationTest --tests dialogcanvas.engine.limit.LimitServiceTest --offline --no-daemon --console=plain
./gradlew.bat build --offline --no-daemon --console=plain
node scripts/verify-dev-auth-guard.mjs
python scripts/verify-stage7-paper.py
python scripts/verify-stage7-audit.py
```

先针对性验证，再完整构建；复现时最终 XML 必须来自全量 build，不能用筛选运行覆盖全量结果。Windows 本轮 Gradle 还保留既有 `-Dorg.gradle.jvmargs=-Dfile.encoding=GBK` 兼容参数。

隔离脚本仅从 build/stage4-paper 的已知测试夹具读取缓存和测试配置，创建全新 build/stage7-acceptance-paper-*，不引用原服务器路径；只对自己启动的进程正常 stop，不清理历史实例。首次沙箱运行在 TCP 查询被拒后已 finally 停止，获准重跑后 13 项通过；开发代理首次 spawn EPERM 后获准重跑 4 项通过。这些是验证环境限制，没有伪报成产品错误或通过。

验收脚本只汇总脱敏计数、位置和结果，不输出 Cookie、CSRF、密码、哈希或扩展输出。stage7-command-results.json 记录实际命令退出结果；重新生成报告不能替代重新执行命令。
