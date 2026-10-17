# 阶段 7.2：文件写入与持久化一致性审计

日期：2026-10-15。以当前生产代码、`PROJECT_CONTEXT.md`、`WEB_API.md` 和现有文件契约为准。

本轮只修改工作区代码、文档和构建产物。测试使用 JUnit 临时目录与 MockBukkit；没有打开、修改、清理原服务器数据，没有替换原服务器 JAR。没有新增 AtomicFileWriter、数据库、文件监听或自动交易重试。

## 1. 结论与确认修复

| 确认的问题 | 修复 | 验证 |
| --- | --- | --- |
| 删除过程中手动 YAML 被修改，旧补偿无条件写回旧 YAML | 删除前再次检查字节；原子分离前再检查；补偿只恢复缺失文件，保留期间新建/修改的 YAML | `manualEditDuringDeleteCancelsDeleteAndCompensationPreservesNewYaml`、`publicationFailureAfterDeleteDoesNotOverwriteNewManualFile` |
| YAML 导出在临时文件写入/force 期间没有再次检查手动文件 | 复用 AtomicFiles 增加 `writeIfUnchanged`，完整写入临时文件后、替换前检查预期字节；密码配置和迁移备份也复用此保护 | `changedManualDocumentIsNotReplacedAndTempIsCleaned`；原有提交期间手改 YAML 与重启保护测试 |
| 发布失败后的正式 JSON 补偿可能覆盖外部新文件 | 只对仍匹配本次新 revision 的正式文件补偿；不匹配则保留文件、返回 503 并停用菜单服务 | `publicationCompensationCannotOverwriteExternallyChangedFormalJson` |
| 限购、生产数据和密码文件的路径检查不完整；Windows junction 可以绕过单纯 isSymbolicLink | AtomicFiles 统一检查真实路径、目录和文件类型；创建目录前检查现存祖先；读、写均检查，目录重定向拒绝 | 实际临时目录 junction 测试：AtomicFiles、LimitService、ProductionStore、AuthService |
| 生产玩家 JSON 写出大小可能大于重启读取上限 | 玩家数据、交易、恢复记录写出前统一检查 4 MiB；限购 YAML 也限制读写大小 | `failedPlayerWriteKeepsFileAndCacheAndOversizeCannotBecomeUnreadableState` |
| 旧迁移在备份已存在、JSON 尚未写入时可能覆盖备份 | 备份仅创建一次；现存备份必须与来源字节匹配，来源在准备与提交前复核；回执校验形状与备份摘要，兼容旧回执 | `interruptedMigrationCannotReplaceOriginalBackupAfterLegacyEdit`；正常迁移/冲突/删除后不复活测试 |
| 成功消息/命令先于限购计数落盘 | 状态动作完成后，先持久化限购结果，再写 effectsPending 阶段，之后执行副作用；任一步失败停止副作用，保留待核查状态 | `quotaAndJournalAreDurableBeforeCommandAndSuccessMessage`、`failedDurableQuotaCommitRetainsReservationAndNeverRunsSuccessEffects`、`failedDurableCheckpointDoesNotSendSuccessOrDispatchCommandAndBlocksRestart` |
| 动作恢复记录 transactionId 与生产交易意图 ID 不同 | 生产链向现有事务引擎传递同一 UUID，日志、恢复 JSON 与交易 JSON 可直接关联 | 退款失败测试检查恢复记录同名交易文件存在且 recoveryRequired |

这些是单文件原子替换与失败保护；不把 JSON、YAML、基线、Vault、背包、限购称为一个原子事务。第 7 节说明仍存在的实际边界。

## 2. 所有物理文件变更入口

下表行号对应本轮源码。逐行调用清单和测试明细由 `python scripts/verify-persistence-audit.py` 生成到 `build-reports/persistence-audit-results.json`。检索还检查了 FileWriter、RandomAccessFile、renameTo、saveConfig/saveResource、输出流及 FileChannel，没有发现另一套生产状态写入器。

| 文件与行号（相对于 plugin/src/main/java/dialogcanvas） | 操作与目标 | 处理方式、边界与失败行为 |
| --- | --- | --- |
| storage/AtomicFiles.java:21、35 | 替换正式文件 | 仅 ATOMIC_MOVE + REPLACE_EXISTING；不支持时直接失败，没有普通覆盖降级 |
| storage/AtomicFiles.java:45、47、50、52 | 创建同目录临时文件、打开、完整写入、force | `.dcmenu-*.tmp` 与目标同父目录；ByteBuffer 循环写到结束，force(true)，关闭后替换；文件内容不会原地截断写入 |
| storage/AtomicFiles.java:59 | 失败临时文件删除 | IOException/RuntimeException 路径清理；清理失败作为 suppressed 保留；进程被杀时见第 7 节 |
| storage/AtomicFiles.java:90 | 创建目录 | 创建前检查最近现存祖先，创建后检查真实路径；拒绝符号链接、junction、非目录重定向 |
| storage/FileStorage.java:31 | 正式/手动/辅助目录初始化 | 使用上述目录检查；记录真实根目录；所有文件名通过 DocumentIds 与父目录边界校验 |
| storage/FileStorage.java:62、100 | 正式 JSON、YAML、基线/导出意图、迁移备份及回执完整替换 | 通用 write 用 AtomicFiles；手动 YAML/缺失备份创建与补偿用 writeIfUnchanged；统一 1 MiB 上限 |
| storage/FileStorage.java:67、68 | 删除正式/辅助文件 | 先 ATOMIC_MOVE 到同目录 `.dcmenu-delete-UUID.tmp`，再删除分离文件；移动失败不降级；分离后清理失败警告并保留临时文件，正式名称已移除 |
| storage/FileStorage.java:103 | 条件删除辅助文件 | 内容检查通过后才原子分离；用于手动 YAML，外部编辑器的极短竞争窗口见第 7 节 |
| config/PluginConfig.java:37、46 | 插件目录、首次 config.yml | 已存在目录检查；默认配置只有目标仍不存在时才创建，不覆盖并发出现的配置 |
| web/auth/AuthService.java:102 | 首次密码哈希 config.yml | 认证工作线程；读取当前完整配置，条件原子替换后才发布哈希并打印一次随机密码；失败不打印、不发布 |
| web/auth/AuthService.java:205 | 修改密码 config.yml | BCrypt 在 worker；同步提交区先核对旧哈希/会话，再条件原子替换完整配置；之后发布内存哈希并清空会话。失败保留旧哈希及会话 |
| engine/limit/LimitService.java:205、218、219 | playerdata 目录与 UUID.yml | 目录/目标检查，4 MiB 读写上限，默认 replacement 固定绑定 AtomicFiles::write；同 UUID 串行，整份 YAML 替换 |
| engine/production/ProductionStore.java:40、140 | 三个业务目录、统一 JSON 写入 | DCMenu-transactions 单文件工作队列；4 MiB 上限；默认 replacement 固定绑定 AtomicFiles::write；所有 UUID 文件读写检查路径 |
| dialog/TestMenuService.java:44、49 | 旧 examples 目录与初始示例 JSON | 固定枚举文件名；AtomicFiles 创建/检查目录和目标，初次完整原子写入。当前插件主类未注册此旧服务；保留供既有测试使用，不是正式菜单写入口 |
| web/auth/InitialPasswordConsole.java:20 | FileOutputStream(FileDescriptor.out) | 仅控制台标准输出，未打开磁盘文件；不是日志/状态文件写入。终端自身是否保存输出由服主环境决定 |

`AtomicFiles.Replacement` 仅为现有工具的函数引用和包内构造器故障注入接口，生产构造器始终使用 AtomicFiles；没有新增写入器实现。菜单持久化接口的条件方法默认兼容既有测试适配器，实际 FileStorage 在临时文件写完后执行复核。

生产插件没有自建追加日志或追加交易流水：交易 JSON 是整份原子替换，恢复 JSON 是按事务 UUID 写整份记录。SLF4J/Bukkit 日志交给 Paper 日志系统，不属于 DCMenu 正式状态；本轮未改日志处理器。未实现的模板/历史 StorageCategory 只有目录初始化，没有宣称已经支持业务读写。

## 3. 逻辑写入调用点与串行规则

| 文件与行号 | 对象/调用 | 顺序、串行及失败处理 |
| --- | --- | --- |
| web/menu/MenuRepository.java:127、131、135 | 迁移备份、正式 JSON、回执 | 整个候选集合先校验引用及主菜单唯一性；备份只创建一次；正式 JSON 逐项提交；按需导出 YAML；最后回执。不以回执存在为理由恢复已由 API 删除的菜单 |
| web/menu/MenuRepository.java:179、180 | API 新建/更新正式 JSON、发布 | 统一菜单 worker 串行整个集合；检查 revision/YAML/引用，准备与往返验证快照，再写 JSON、发布注册表；失败见第 4 节 |
| web/menu/MenuRepository.java:240、242 | 手动单个/批量 YAML 导入 | 当前正式集合的引用检查通过才提交；先 JSON、后注册表、再规范 YAML/基线；批量逐项报告部分成功 |
| web/menu/MenuRepository.java:275–305 | 删除及删除补偿 | 同 worker；检查同步/revision/引用，分离 JSON→条件分离 YAML→分离基线→发布新注册表。补偿恢复缺失文件，保留新手动 YAML；正式/基线被外部改变时停止服务，不假装成功 |
| web/menu/MenuRepository.java:308–323 | 发布失败补偿 | 本次新 JSON 必须仍匹配本次 revision；创建失败删除本次 JSON，更新失败原子恢复旧字节，再恢复旧注册表；补偿失败 503 MENU_STATE_UNCERTAIN |
| web/menu/MenuSynchronization.java:62、66、68 | 导出意图、YAML、最终基线 | 意图先于 YAML；完整临时 YAML 写好后复核原 YAML；最终基线最后提交。SHA-256 字节摘要，不使用 mtime；基线 hash/pending 必须是有效字符串 |
| engine/production/ProductionStore.java:67 | begin 交易意图 | 原子持久 pending 后才返回收据、允许业务执行，并阻止同玩家重复交易；意图写入失败不执行业务 |
| engine/production/ProductionStore.java:78 | effectsPending / 最终状态 | 先保存阶段再继续副作用；最终状态写入成功且为 committed/rolledBack/denied 才解除玩家持久阻止。effectsPending 带 stateCommittedAt，不冒充 finished |
| engine/production/ProductionStore.java:96 | player_data_set/increment | 全量 next 快照先写文件、后发布缓存；失败缓存不变 |
| engine/production/ProductionStore.java:103 | 玩家数据补偿 | 比较当前字段与本次 after；逆序恢复先写文件、后更新缓存；外部变更/写入失败不假装补偿成功 |
| engine/production/ProductionStore.java:127 | 交易恢复 JSON | 主线程只捕获/序列化 Bukkit 物品恢复资料，纯 JSON 在 worker 写入；失败返回 RECOVERY_SAVE_FAILED，原有交易意图仍保留并阻止重试 |
| engine/limit/LimitService.java:143、167 | 预留、计数/冷却提交或释放 | 每 UUID 尾链覆盖检查、预留、业务与收尾；预留必须先落盘。成功状态动作后先落盘计数/冷却，再继续副作用；失败保留 pendingQuantity，重启拒绝再次购买；回滚不消耗额度 |
| engine/production/ProductionActionEngine.java:64、95、67 | 生产整体顺序 | pending 意图→限购预留→主线程状态动作→限购结算→effectsPending→副作用→最终收据。检查点失败不发送成功消息/命令、不自动重试；最终收据失败保留 effectsPending 并阻止重试 |

菜单 worker 也串行不同菜单，避免并发交叉引用提交；FileStorage 自身同步方法提供实例内保护。生产文件单 worker 串行；LimitService 不同 UUID 可并行，同 UUID 不可并行；认证哈希提交有同步区。这些是单进程、单实例保证，不是跨进程文件锁。

## 4. 菜单提交、补偿及响应

1. 模型、业务配置、引用、revision 和同步状态全部在写文件之前检查。规范 JSON 和 YAML 都先准备，JSON 往返与不可变注册表快照先验证。
2. JSON 替换失败：注册表、YAML、基线均不更新；返回 STORAGE_WRITE_FAILED。
3. JSON 成功、注册表发布失败：核对仍是本次新文件后，恢复旧 JSON/旧注册表；正常补偿返回 REGISTRY_PUBLISH_FAILED。外部文件改变或补偿失败返回 503 MENU_STATE_UNCERTAIN，菜单服务不可用。
4. JSON/注册表成功后，导出 intent→条件 YAML→最终 baseline。任一导出步骤失败，保留正式提交结果，POST 201 / PUT 200 带新 revision/menu 与 YAML_SYNC_FAILED；不得让网页按全失败重复提交。
5. 导入失败不会发布无效引用；批量失败不撤销已经明确成功的其他菜单，不承诺多个菜单文件整体原子。
6. 删除失败先恢复正式文件与旧注册表；未导入/期间新改 YAML 保留。发生可确认的手动冲突返回 409 YAML_SYNC_CONFLICT，其余失败 500，无法补偿 503；不会返回 204。

未改路由、Cookie、CSRF、revision 不透明字符串和已有部分成功语义。不会因 unknown/syncFailed 自动覆盖手动文件。

## 5. 中断恢复依据

| 中断点 | 磁盘依据 | 启动/操作行为 |
| --- | --- | --- |
| 临时文件写完、JSON rename 之前 | 旧正式文件 + 隐藏临时文件 | 正式 JSON 仍加载旧状态，隐藏临时文件不作为菜单加载 |
| JSON rename 后、注册表发布前 | 新完整正式 JSON | 重启重新验证/加载 JSON；调用端当时结果不确定，应读取核查，不能推定保存失败 |
| JSON/注册表已提交、intent 未提交 | 新 JSON、旧/缺失基线 | 不猜测 YAML；同步不明时提示核查，不覆盖现有手动文件 |
| intent 已提交、YAML 未写 | jsonHash + yamlHash + pending | 只有 JSON 匹配意图，且 YAML 仍匹配旧摘要/已知 missing，才补完 |
| YAML 已写、最终 baseline 未写 | pending 与 YAML 新摘要匹配 | 无手动改动时补最终基线；YAML 又被修改则保留文件、syncFailed，不反复覆盖 |
| JSON/YAML/基线删除中途 | 缺失的正式文件、残留 YAML/基线或分离临时文件 | 不当作多文件原子删除；残留基线使手动 reload 拒绝重新创建，停服核查。全部正式文件已删除而迁移回执仍在，也不从旧 legacy YAML 复活 |
| 迁移备份后、JSON/回执之前 | 原 legacy YAML + 只读意义的原字节备份 | 来源与备份相符、JSON 无碰撞才续跑；来源改变、备份不符或损坏回执则停止，不替换备份 |
| pending 交易或限购预留之后 | 交易 menu/inputs 快照、pendingQuantity | 重启阻止交易，不猜测经济/物品是否已变更，不自动重试扣款或发货 |
| 限购已提交、effectsPending / 最终收据之间 | 已持久计数 + 交易阶段记录 | 保持已购计数；未完成阶段阻止玩家重试，副作用是否发生由服主核查 |
| 补偿或恢复记录写失败 | 原 pending / recoveryRequired 意图、可能缺失恢复细节 | 明确待人工核查；不宣称全回滚，不删除意图解除阻止 |

基线缺失/损坏时，现有手动 YAML 受保护；不通过 mtime、JSON/YAML 恰好看起来相似或重新计算猜测的基线来覆盖。首次没有 YAML/基线的合法创建仍可建立初始同步。

## 6. 故障注入与实际验证

完整构建：使用 Java 25，离线 Gradle 9.4，`gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'`。首次沙箱内构建的 Vite 子进程被 EPERM 拒绝；获准沙箱外运行后完成编译/前端资源构建/Shadow 打包/Java 测试。没有升级依赖。

新增与扩展的验证覆盖：

- ATOMIC_MOVE 不支持：故障函数在同目录完整临时文件上抛 AtomicMoveNotSupportedException；旧文件逐字节不变、临时文件清除，没有 fallback。
- YAML 补偿竞争、正式 JSON 补偿竞争；写入/发布后抛错/补偿失败、引用删除、同 revision 并发等原有测试也继续运行。
- 导出 intent 写入失败、YAML 写失败、最终 baseline 写失败；重启允许已知匹配恢复，额外手动改动与再次重启/保存均保留。
- 迁移备份后写失败再手改 legacy、正常迁移及碰撞、API 删除后旧 legacy 不复活。
- 实际 Windows junction（仅两端均在测试临时目录）验证 AtomicFiles、限购、生产数据及密码配置拒绝目录重定向。
- 玩家数据写入失败和超读取上限：磁盘与缓存保留旧值。
- 限购最终提交失败：保留预留，成功副作用未执行，重启继续阻止。
- 交易阶段写失败：不发送成功消息、不派发命令；最终收据失败：保留 effectsPending；退款/恢复文件失败：仍有持久阻止依据。
- 恢复记录与意图 UUID 关联；现有 delay、return、逆序回滚、命令 player/op/console 与 OP finally 恢复等回归继续运行。

准确测试数量、每个用例结果、调用点和 JAR SHA-256 以 `build-reports/persistence-audit-results.json` 为准。本轮没有真实 Paper 服务器或 Minecraft 客户端验证；不是硬断电/杀进程实验，恢复路径通过保留磁盘状态、重建服务实例验证。

最终结果：**401 项 Java 测试，398 通过、3 项既有 Provider 限制跳过、0 失败/错误**；本轮新增 21 项并扩展既有测试。完整 Gradle build 成功，JAR 为 `plugin/build/libs/DialogCanvas-0.1.0.jar`（11,347,438 字节，1111 个前端文件）。SHA-256：`beb23ee0487d57433dfacaef70c57d77ced6c3fa9fa815012110090b88f5aa11`。

## 7. 未覆盖或不能承诺的边界

1. **外部编辑器竞争不是原子比较并替换。** 现在准备后、写完临时文件后/分离前都检查内容，已修复能确认的覆盖路径。Java NIO 没有通用的“比较内容并 rename”原语；不参与同一锁的外部编辑器仍可能在最后一次检查与 rename 之间保存。不能宣称此极短窗口被完全消除；严格排除需要编辑器配合锁定/编辑协议。手动编辑时应避免同时从网页保存/删除同一菜单；正式 JSON/基线不要在运行中手改。
2. **文件原子替换不等于断电持久性。** force(true) 确认临时文件内容刷新，但未做跨平台父目录 fsync，不承诺突然断电、磁盘控制器缓存/故障下元数据一定持久。测试覆盖正常异常和重启，不覆盖硬断电。
3. **不是跨插件 ACID。** Vault/背包与文件不是同一数据库事务；进程在经济/物品步骤之间中断时，原 pending 意图用于阻止重复交易，未承诺自动精确恢复任意插件余额或离线背包。需服主按记录核查；恢复 JSON 失败时细节可能不足。
4. **最终收据可能失败于已发生副作用之后。** 之前的限购和 effectsPending 已持久化；命令/消息可能已经发生，不能逆向撤销或自动重放。保留该阶段、阻止再次交易并明确报告失败。
5. **多文件删除/迁移不整体原子。** 中断可能留下部分文件；不得仅删基线或待处理交易来强行继续。已有路径不自动清理疑似残留，以免丢失恢复依据。
6. **临时文件与日志生命周期。** 正常异常尽量清理临时文件；进程被杀或清理失败时留下隐藏 temp，正式加载忽略它们；没有自动清理原数据的工具。交易记录没有新增自动过期清理。
7. **单实例目录所有权。** 不支持两份插件/服务器同时共享菜单、玩家文件或 config.yml；实例内串行规则不能代替跨进程协调。目录安全检查也不承诺对有系统权限、不断重定向目录的对抗程序实现不可绕过隔离。
8. **测试范围。** 文件权限/空间失败采用确定性故障注入；没有真的填满磁盘、破坏原服务器、切换生产卷或连接真实 Vault/客户端交易。本轮未修改前端业务，Gradle 复用现有前端构建输入。
