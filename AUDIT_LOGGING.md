# 阶段 8.4：管理操作审计日志

阶段 10.2：显示层共用错误码文案，管理提示包含诊断码，生产交易诊断包含现有 transaction UUID。实际业务状态、审计完成点及固定字段均保持；反馈不创建新账本、不推断提交成功或回滚。菜单展示和管理分发的错误诊断也改为固定说明，不附第三方异常原文/堆栈。未知诊断码格式受限，详细结果区别及证据见 ERROR_FEEDBACK.md。

阶段 10.1 补充：审计格式不变；另修复服务器普通动作诊断日志中的第三方异常原文/堆栈输出，改为固定诊断码、事务 UUID 和结果状态，不附命令参数或异常 Throwable。此规则不改变内部异常传播或私有交易恢复文件；第三方插件自己的日志不受本插件过滤。具体复现与验证见 STAGE10_SECURITY_CONFIG.md。

阶段 8.5 命令扩展沿用本系统：list 记录 LIST_SHOWN/LIST_EMPTY 或页码拒绝；edit 规范为 dcmenu:editor，根别名仍使用 dcmenu 固定目标。open 缺目标/离线为 REJECTED，实际展示 Future 完成才记完成，取消为 OPERATION_CANCELLED；不记录命令参数、菜单展示正文或目标玩家输入。具体权限、别名冲突和复现见 MANAGEMENT_COMMANDS.md。

## 文件与格式

- 路径为插件自己的 `logs/audit.log`，例如 `plugins/DialogCanvas/logs/audit.log`，不是服务器根目录的 logs。UTF-8、JSON Lines；正常写入的每一行是一个完整 JSON 对象，以 LF 结束，由 Jackson 转义而非字符串拼接字段。
- 单个文件加上下一条记录将超过 **10 MiB（10 × 1024 × 1024 字节）** 时先轮转；历史名为 `audit-<UTC毫秒>-<随机UUID>.log`。独占写锁、唯一名称、目标不存在检查和原子移动；不覆盖已有历史，不在原子移动失败后改用普通覆盖。
- 日志、历史和 `.audit.lock` 都固定在插件 logs 下，不接受 API/命令提供路径。沿用 AtomicFiles 的目录、符号链接、真实路径及文件类型边界检查。新文件继承运行账户的文件系统权限/ACL；不擅自修改现有文件权限。日志不通过网页静态资源服务暴露。
- 这些是追加日志，不为每行创建 AtomicFiles 临时文件；菜单、配置和玩家数据继续使用原有原子写入流程。

示例（演示标识，不是实际会话或密码）：

```json
{"time":"2026-10-16T00:00:00Z","eventType":"MENU_DELETE","operationId":"3bef2c82-969c-4dc5-8623-ef40d30207fe","source":"web","actor":"local-admin-session","target":"example_shop","result":"FAILED","code":"MENU_REFERENCED","beforeRevision":null,"afterRevision":null}
```

| 字段 | 含义 |
| --- | --- |
| time | 记录实际结果时的 UTC Instant，不是请求到达/玩家最后上线时间 |
| eventType | 固定事件类型，见下表 |
| operationId | 随机操作 UUID；归档扫描及其逐玩家归档共用 ID；管理命令、其调用的服务操作、批量导入各项可有独立 ID |
| source / actor | web / local-admin-session；console / console；player / 玩家UUID；system / system。调度前拒绝、自动任务或其他非玩家命令来源使用 system，不猜玩家身份 |
| target | 菜单 ID、玩家 UUID、备份 UUID、固定管理子命令标识或 `*`；非法值只记 `<invalid-target>` |
| result / code | 固定结果枚举及诊断码，不记录异常原文 |
| beforeRevision / afterRevision | 从服务端注册表取得的前后菜单 revision；不适用时 null，不写入客户端伪造的 revision。菜单 revision 摘要不同于密码哈希 |

## 覆盖范围与完成点

| 事件 | 记录点与语义 |
| --- | --- |
| MENU_CREATE / MENU_UPDATE | 现有 MenuRepository 集合协调器内，文件、发布和 YAML 同步步骤结束后记录。正式提交成功但 YAML 导出失败为 PARTIAL / YAML_SYNC_FAILED，保留正式 afterRevision |
| MENU_DELETE | 文件及注册表删除完成后 SUCCESS；被引用、同步冲突等失败记 FAILED；补偿失败记 UNCERTAIN / MENU_STATE_UNCERTAIN，不宣称已回滚 |
| YAML_IMPORT / YAML_IMPORT_BATCH | 控制台手动导入；每个菜单一个实际结果，另有批量汇总。部分失败或 YAML 导出失败为 PARTIAL，成功项不撤销。普通启动加载和既有一次性迁移不作为新的手动导入事件 |
| RETENTION_SCAN | dry-run/归档扫描完成后记录；有失败或被停服打断为 PARTIAL。保护状态导致跳过不等于扫描失败，也不等于清理成功 |
| RETENTION_ARCHIVE | 每个被检查的标准玩家文件，真正原子替换之后 SUCCESS；无可清理条目 SKIPPED；保护/错误诊断保留，失败不谎报归档成功 |
| RETENTION_RESTORE | 实际恢复之后 SUCCESS，已经是原内容 SKIPPED，新状态冲突 FAILED |
| PASSWORD_CHANGE | AuthService 内，哈希持久化并更新内存、失效会话之后 SUCCESS / PASSWORD_CHANGED；校验或持久化失败记录失败码，不记录新旧密码或哈希 |
| MANAGEMENT_COMMAND | 现有 dcmenu 命令入口；权限、用法、服务未就绪等为 REJECTED。异步打开/重载/维护在真实 Future 结果后记录，不把“已提交后台”当成功；批量部分成功为 PARTIAL。仅记录固定子命令，不记录完整命令行或参数 |

已认证 Web 写请求在分发前因请求体、Origin/CSRF 等被拒绝时，记录对应操作的 REJECTED 结果；业务已分发的请求由服务记录，不再重复记 HTTP 结果。未认证请求不冒充管理员会话。读取、列表、会话检查、元数据、校验未保存文档、临时预览、连续输入、普通点击交易均不产生编辑审计；管理命令本身仍作为明确命令记录。

生产扣款、发货、限购预留、补偿和恢复继续以现有 `transactions` / `recovery` 持久记录为依据。本日志不是交易账本，也不是自动补偿、恢复或重试的依据。

## 敏感信息边界

只接收固定事件、来源/操作者、目标、结果、码和合法服务端 revision，没有任意 request/map/details/Throwable 字段。单管理员网页操作固定标记 `local-admin-session`，不使用 Token 指纹。玩家身份只记 UUID，不读名字。

**不记录**密码、BCrypt 哈希、Cookie、CSRF、实际 Token、完整菜单正文、命令参数、玩家业务数据、占位符结果或原始异常消息。目标及诊断码有字符/长度限制；换行注入不能形成伪造记录。测试脚本中的公开隔离测试凭据不是真实服主密码，报告也不输出其值。

## 队列、失败和停服

1. 生产固定为容量 1024 的后台有界队列和一个平台写线程；业务线程只尝试入队，不等待磁盘、不阻塞 Minecraft 主线程。文件扫描/追加/轮转/锁和 force 均由写线程执行。
2. 队列满或已关闭时拒绝该日志，增加 rejected，立即以安全的 SEVERE 诊断输出操作 ID、事件类型和 AUDIT_QUEUE_FULL / AUDIT_STOPPED。不会静默丢弃，也不会为腾出空间删除旧记录。已成功入队不等于已落盘。
3. 初始化、锁、追加或轮转失败会明确报警；不输出异常中的私密内容。追加/轮转失败后写线程停止后续文件追加，逐个将剩余事件记为 failed 并报警，不盲目重试可能已写了一部分的同一事件。队列满本身不停止仍可工作的写线程。`stats()` 提供 accepted/written/failed/rejected/stopped/healthy；本次出现过失败后 healthy 保持 false。
4. 业务结果与日志结果分开：日志失败不会改 revision、回滚菜单、改变已提交密码或恢复交易，也不会改成功 HTTP 为失败来诱使客户端重试。控制台报警明确写着业务结果不变、不要重试已提交操作。故障期间可能有审计缺口，应结合正式状态和已有交易记录人工核查。
5. 如果插件诊断 Logger 自己抛异常，尝试固定平台 Logger 报警；不将异常传播到业务。如两个诊断通道都损坏，内存健康状态/计数仍失败，但无法保证当时有外部可见报警；不能凭业务成功断言审计完整。
6. 停服先停止并排空菜单、认证、归档等生产者，再关闭审计准入并排空已接受事件，释放文件锁。closeAsync 完成表示队列已处理完，不代表曾失败的事件已补写；失败计数与报警仍有效。沿用已批准的 onDisable 最多 30 秒总有界等待；磁盘挂起等超时会报告收尾未完成，不承诺无限等待。

## 重启和实际保证

- 每条记录完整循环写入并调用 `force(false)`；单进程写线程及协作文件锁避免并发追加相互穿插。重启保留有效已有日志继续追加。
- 启动检测非法 JSON、非法 UTF-8、过长行或不完整尾行时，先把原始文件轮转保存，明确输出 AUDIT_INVALID_EXISTING_ROTATED，再从新文件写合法记录。异常历史可能含不完整尾行，保留字节供排查，不偷偷截断。轮转失败则不追加。进程故障造成的半行不能假装已修复或已成功写入。
- 不承诺防篡改、哈希链、断电零丢失或业务与审计的跨文件原子事务。业务刚提交但事件尚未入队时进程退出，或已入队但尚未写盘时崩溃，都可能缺记录。独占锁只协调使用同一协议的实例，不保证与外部编辑器/恶意替换路径并发安全。
- 不自动删除历史、异常日志或锁文件，不自动恢复/重试缺失的审计事件。运行时不要手改或替换当前日志；离线备份/检查历史由服主管理。

## 验证与复现

针对性测试：AuditLoggerTest（并发、JSON 行与字段、10 MiB 边界、唯一轮转、队列满、停止排空、追加/轮转失败、半行重启、路径/协作锁、诊断 Logger 失败）；MenuAuditTest（CRUD、revision、导出部分成功、发布失败/补偿不确定、批量导入、日志故障不改业务、取消调用方）；另覆盖 HTTP、密码脱敏、管理命令异步结果和归档恢复审计。

```powershell
./gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
python scripts/verify-audit-paper.py
python scripts/verify-audit-artifacts.py
```

Paper 脚本只在工作区 `build/audit-paper-*` 使用已有固定版本缓存和隔离测试配置，随机本地端口，两次启动/正常 stop，真实 HTTP CRUD/密码修改、控制台 YAML 导入、归档恢复、重启和排空后验证 JSON 行及敏感值排除。不访问或修改原服务器。实际结果与未验证项见 build-reports/audit-acceptance-results.json、audit-paper-results.json。

### 最终结果（2026-10-16）

- 完整 Gradle build 成功：579 项 Java 测试，575 通过、4 项既有跳过，0 失败/错误。新增 20 项，其中日志单元 11 项、菜单审计 6 项、HTTP/归档/管理命令各 1 项；跳过是 3 项既有原生 Dialog provider 及 1 项 MockBukkit 自定义物品默认属性能力缺失，不算通过。
- 同一最终 JAR 的隔离 Paper Build 74：13 项通过，两次实际启动/正常 stop。真实 HTTP、控制台、配置文件、审计日志及端口验证不等于已连接客户端验证；没有真实玩家、Vault/PAPI 交易或断电/磁盘硬件测试。
- 最终 JAR：dist/DialogCanvas-0.1.0.jar，SHA-256 为 bbba4026134dee1a54228bfbd0cb4d5a9dd6c27020459bf5a29486695777a93e。新增审计类和现有 1050 个前端资源均已检查；前端未修改，没有新增 TypeScript/前端测试运行。
- 原服务器 JAR、密码、菜单和玩家数据未读写或部署。完整原子业务流程及交易记录仍是事实依据，日志缺口不得用自动重试弥补。
