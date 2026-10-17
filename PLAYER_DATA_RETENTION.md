# 阶段 8.3：玩家数据保留、清理资格与恢复规则

## 实际位置与保留语义

以下路径以插件目录为根；`data` 代表 config.yml 的 data-directory，不能逃出插件目录。

| 数据 | 路径 | 保留规则 |
| --- | --- | --- |
| 限购/冷却 | playerdata/<玩家UUID>.yml | 本阶段只移除明确过期的 limits 条目，不删除整个文件 |
| 玩家业务数据 | data/player-data/<玩家UUID>.json | 默认永久保留；任意业务字段不是过期计数，不清理、不驱逐此缓存 |
| 交易意图/最终收据 | data/transactions/<交易UUID>.json | 全部保留；pending、effectsPending、recoveryRequired 及未知状态阻止该玩家清理 |
| 补偿恢复记录 | data/recovery/<交易UUID>.json | 全部保留；存在该玩家的恢复记录即跳过，不自动判断已处理 |
| 清理/恢复备份 | data/retention-backups/<备份UUID>/record.json | 永久保留，独立 UUID 目录独占创建，永不覆盖旧版本，不自动清理 |

LimitService 不缓存玩家 YAML，每次读取磁盘；同 UUID 的操作有 Future 队列。ProductionStore 缓存不可变业务 JSON，单文件工作线程；begin 先持久化交易意图再标记 blocked，最终持久收据后解锁。清理不能绕过这两个机制。

## 资格（先定义再实现）

- 不使用文件 mtime，不声称获取了玩家最后上线时间。配置的天数表示“状态到期后额外保留的天数”，不是离线天数。
- 新成功限购提交补充 resetMode、lastCommittedAt；原计数、resetAt、cooldownUntil、pending 字段及业务语义保留。旧记录不自动补猜元数据，不借清理迁移它。
- 只接受已识别的条目结构、非负整数 count、可靠的 UTC 时间及已识别的非 NEVER 重置类型。resetAt 和 cooldownUntil 都必须存在、均已到期，且较晚到期时间已超过配置保留天数。lastCommittedAt 必须可靠且不晚于到期时间。
- NEVER 永久保留；pendingQuantity/pendingSince 任一存在均保留；未来计数窗口、未来冷却、缺时间、未知字段/类型/损坏文件均保守跳过并给出原因。DAILY/WEEKLY/MONTHLY 已由限购服务按服务器时区计算为 Instant；TIMED 使用保存的实际 resetAt，不重新猜起点。
- 生产维护还必须与正式菜单中的当前限购规则匹配；共用生产执行器的限购 key 算法。菜单/按钮已删除、规则改成 NEVER 或其他重置类型、共享 scope 的重置类型不一致时跳过（CURRENT_RULE_NOT_CONFIRMED）。无法匹配的孤立旧记录不擅自删除。当前规则核对和实际替换都在原 MenuRepository 集合协调器内，不能在核对后插入菜单配置修改。
- 运行中的交易、未决持久交易、任何恢复记录、无法识别的交易/恢复文件阻止清理。无法归属玩家的损坏记录使本次维护保守跳过，不能借日期绕过。
- 同一文件可同时保留永久条目并移除过期条目；根文档及其他条目原样保留，不删除整个 YAML，空 limits 文档也保留。
- 第一版维护上限为单个限购 YAML 512 KiB；更大的文件跳过，不把无法备份的文档当作空文件。只处理标准 UUID 文件名，拒绝符号链接、目录、路径跳转和重解析目录。

## 提交、并发与中断

1. 默认关闭自动任务；默认模式 dry-run。dry-run 不修改玩家文件、不创建备份，报告候选条目、跳过原因与计数。
2. 检查/归档先在 ProductionStore 的文件线程取得玩家维护门禁，阻止新的 begin；已开始交易使维护跳过。持久记录扫描在独立后台虚拟线程进行，避免占用交易写入线程；每 200 条让出一次。再进入 LimitService 原 UUID 队列，等已有操作完成。
3. 队列内重新读文件、重新计算资格并核查状态；对候选进入当前菜单集合协调器核对规则并完成备份/替换。规则核对也是 dry-run 的组成部分，不能复用 dry-run 结果作为执行凭证。菜单协调器可能短暂等待文件操作，但没有 Minecraft 主线程阻塞；常规读取只用原不可变快照。
4. 先在独占新目录中以 AtomicFiles 完整保存 before/after 内容及 SHA-256，并重读验证；备份失败不修改原文件。备份完成后再次检查内存不确定状态、持久交易/恢复目录和当前时钟下的条目资格；新增未决状态或时间回拨使资格改变时立即停止，不修改原文件。
5. 通过 AtomicFiles.writeIfUnchanged 原子替换 YAML。失败不回退普通覆盖；备份后源文件变化则冲突，留下备份供核查。最后释放维护门禁；调用方取消不会提前释放真实工作。
6. YAML 没有旧缓存可写回；业务 JSON 和其缓存从不修改。插件停止先停止接收维护任务，等待已接收任务结束；既有交易停服协议保持。
7. 多文件不是原子事务。进程在备份后、替换前/后中断时，完整备份的 before/after 摘要就是判断依据，不自动重放、不删除备份。

## 恢复

- 只接受备份 UUID，路径固定在 retention-backups 下。检查文档版本、归属玩家、时间、两份内容/摘要、YAML 结构及大小；不接受任意源/目标路径。
- 与清理使用同一交易门禁和 UUID 队列，重新核查未决交易/pending/恢复记录。
- 当前文件等于备份 before：视为尚未应用或已经恢复，不重复写。
- 当前文件等于备份 after：可恢复；先为当前内容生成新的独立恢复备份，再原子恢复 before。
- 当前文件缺失或匹配两者之外的任何内容：拒绝覆盖，提示人工核查。不能覆盖归档后的新购买或手工编辑。
- 恢复不回放交易、不发货、不改余额，不移除 pending/交易/补偿记录，不自动清理任何备份。

## 入口与自动任务

入口已实现：仅服务器控制台 `dcmenu data dry-run [玩家UUID]`、`dcmenu data archive [玩家UUID]`、`dcmenu data restore <备份UUID>`。restore 与 archive 都是明确的管理写操作，游戏内 OP 也不能直接执行；控制台自身不需要浏览器会话。自动任务在生产服务就绪后触发启动检查，并可每 24 小时后台执行（不是零点或按 mtime 执行）；每批数量可配置，批间让出工作线程，停止时不再扫描后续批。控制台只输出 UUID、经过安全字符检查的限购键、原因码及统计，不打印玩家业务值或交易输入。单次展开最多 200 项，超出保留完整统计并提示按 UUID 复查。候选/归档为限购条目数；失败为文件数；“跳过”按诊断项计数，文件级保护原因计 1 项，不冒充已逐条分析。

```yaml
player-data-retention:
  enabled: false
  mode: dry-run
  on-startup: true
  daily: true
  expired-days: 30
  batch-size: 20
```

旧配置不含此节时仍默认关闭；修改后重启。expired-days 允许 0～36500，batch-size 允许 1～200；mode 只接受 dry-run/archive。不要先改为自动 archive；先在控制台 dry-run 核对。archive 每次都重新判断，dry-run 不生成以后可盲目执行的“批准清单”。

## 边界

- 只对保存的重置模式和真实到期字段给出资格。旧文件没有这些证据时会保留，不能承诺清理所有历史限购。冷却独立使用 NEVER 的记录也保守保留。
- 每个玩家会扫描持久交易/恢复目录；目前没有按玩家建立独立磁盘索引。大目录下维护可能耗时，但扫描不占 Minecraft 主线程或正常交易文件写入线程，逐批让出且可停止；同玩家的新点击在维护期间会被拒绝，请稍后再试。
- 同一进程、单实例独占目录是协调前提。外部编辑器需错开维护；writeIfUnchanged 检查与文件系统 rename 之间不具跨进程 CAS，不能承诺任意并发外部写入或硬断电的零覆盖。SHA-256 验证完整性，不是防止服主恶意改写备份的签名。
- 恢复是精确匹配 before/after 的保守恢复，不按条目合并；有新交易、文件缺失或其他修改时拒绝，不支持强制恢复。损坏的备份、不完整目录应保留并人工核查。
- 备份与源文件不是多文件原子事务；正常故障/进程重启依据完整备份判断，不自动重放。文件/目录硬断电持久性仍受现有 AtomicFiles 和文件系统保证限制。

未授权部署，不操作原服务器数据。验证证据在下节记录。

## 写入点与协调依据

| 位置 | 实际操作与保护 |
| --- | --- |
| LimitService.java:164 | 成功结算原 YAML 原子写入同时补充 resetMode/lastCommittedAt；UNKNOWN/pending 不生成清理资格 |
| LimitService.java:238 | maintain 与购买共用原 UUID tails；返回副本，取消调用方不会提前释放队列 |
| ProductionStore.java:63、:150 | begin 与维护门禁在同一写线程准入；维护不修改业务 JSON 缓存，门禁在真实 Future 完成后释放 |
| ProductionStore.java:171 | 提交前重新核查门禁、blocked/uncertain 及持久交易/恢复记录；关闭等待维护检查结束 |
| RetentionArchive.java:24、:31 | 独占创建 UUID 目录，AtomicFiles 写 record.json、重读内容/摘要；不覆盖旧备份 |
| MenuRepository.protectLimitRetention / ProductionActionEngine.limitKey | 当前规则核对复用生产 key，并在原集合协调器内与写入一起执行；保留当前永久限购，拒绝孤立/类型变更/歧义共享规则 |
| PlayerDataRetention.process | 确认有效备份及最终资格后 writeIfUnchanged 原子替换 YAML，不回退普通覆盖 |
| PlayerDataRetention.restore | 恢复也先独立备份，再 CAS 恢复；新状态不匹配即冲突 |
| DialogEditorPlugin.initializeServices / releaseResources | 生产与菜单服务就绪后启用可选任务，停服先停止维护准入，所有维护 I/O 与等待留在后台；延用已批准的 onDisable 最多 30 秒总有界等待 |

此功能没有菜单 JSON/YAML/同步基线写入点，没有新增 REST 或数据库，没有自动删除玩家文件、交易/恢复记录或备份的调用。

## 验证结果（2026-10-16）

| 场景 | 证据与结果 |
| --- | --- |
| 永久限购、未来周期/冷却、旧数据缺时间、未知字段、pending | PlayerDataRetentionTest：全部保留；mtime 不影响资格 |
| DAILY/WEEKLY/MONTHLY/TIMED 与到期/保留天数边界 | 实际状态字段计算，通过精确边界、未来时间与时钟回拨测试 |
| 当前菜单规则改变、孤立旧键、共享 scope 冲突 | LimitRetentionPolicyTest 3 项通过；规则修改与清理在集合协调器中串行 |
| 未决交易/恢复记录、无法识别记录、备份后出现恢复记录 | 保守跳过，业务 JSON、收据及恢复记录保持；重启后继续阻止 |
| 并发交易、原 UUID 队列、取消调用者、生产购买联动 | 原队列和真实任务门禁有效；购买期间拒绝维护，维护期间拒绝新交易，不提前释放锁 |
| 备份失败、替换前源文件变化、损坏备份、恢复前备份失败 | 不清理或不恢复；不覆盖旧备份，不覆盖归档后新购买/手动修改 |
| 中断与重启 | 完整备份后未提交可判定已是 before；重启可按同一备份恢复，未知状态不自动重放 |
| 自动任务与关闭 | 默认关闭；启用后启动 dry-run/archive 两种模式后台验证；停止排空任务、不继续扫描后续批 |

- 完整 Gradle 构建成功：559 项 Java 测试，555 通过，4 项既有跳过，0 失败/错误。跳过为 MockBukkit 未提供的 3 项原生 Dialog 构建及 1 项完整自定义物品默认属性测试，不计通过。本轮新增 PlayerDataRetentionTest 37 项、LimitRetentionPolicyTest 3 项，另有生产引擎、配置及命令回归。
- 独立工作区 Paper 26.1.2 Build 74：9 项实际控制台/文件/生命周期检查通过，涵盖默认关闭、dry-run 不写入、路径拒绝、独立备份、精确归档/恢复、变更冲突、未决记录重启保留、两次停止与 HTTP 端口释放。隔离实例没有连接 Minecraft 客户端，也没有真实 Vault/PAPI 交易；未实际等待 24 小时定时周期，不能把定时注册/启动任务测试当作等待一整天的验证。
- JAR 已导出到 dist/DialogCanvas-0.1.0.jar；新增类及默认关闭配置存在，1050 个前端资源与已有 frontend/dist 逐字节一致。前端未修改，本阶段未重跑 TypeScript/前端测试。
- 汇总 build-reports/retention-acceptance-results.json；Paper 证据 retention-paper-results.json、retention-paper.log；JUnit plugin/build/test-results/test。原服务器 JAR、配置、密码及玩家数据均未修改。

### 复现

在项目根目录、Java 25 环境执行：

```powershell
./gradlew.bat :plugin:test --tests dialogcanvas.engine.retention.PlayerDataRetentionTest --tests dialogcanvas.web.menu.LimitRetentionPolicyTest --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
./gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
python scripts/verify-retention-paper.py
python scripts/verify-retention-artifacts.py
```

隔离脚本需要工作区已有 build/stage4-paper 的固定版本缓存及隔离测试配置，使用随机本地端口和新建 build/retention-paper-* 目录，不读取或写入原服务器。最后一条要求完整测试报告及同一 SHA-256 的 Paper 通过结果，随后导出工作区 dist JAR；不会部署。
