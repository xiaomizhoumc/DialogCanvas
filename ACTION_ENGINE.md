# 步骤 3.3：动作引擎

## 阶段 7.4 点击入口（2026-10-15）

正式按钮先验证点击者、当前打开会话、回调次数/有效期及正式菜单版本，再以 `(玩家 UUID, 菜单 ID, 按钮 ID)` 进入现有 ClickGuard。锁不含会话号或修订号，刷新/重开不会释放正在执行的操作；完整业务 Future 包含条件、拒绝分支、状态动作、补偿、限购、提交后副作用、刷新和最终交易记录。纯关闭不调用业务引擎，none 无回调。

生产引擎的玩家级 active 入口拒绝同玩家并发业务（不排队积压点击）；底层动作队列及 LimitService 则按 UUID 串行。调用方取消外层结果不会取消内部结算或提前释放队列。ClickGuard 只是进程内执行期间的排重，不是持久幂等；不确定交易由 ProductionStore/LimitService 的持久记录阻止重试。入口表、生命周期例外及原版关闭观测限制见 CLICK_ENTRY_AUDIT.md。

## 当前生产接入（2026-10-14）

下文早期验证和“先接口后适配器”描述保留为历史记录。当前 ProductionActionEngine 已安装到 DialogRenderer，并保留 executeAsync/ClickGuard；VaultEconomyAdapter、ProductionStore、真实 MenuRepository 打开/刷新及 LimitService 已接入。复用原有动作模型，不新增购买 Web API。

Vault 兼容接口在主线程通过实际安装的 API 反射调用，检查交易响应和实际余额差额；退款补偿按变更额执行，不覆盖整个余额。安全拒绝与结果不确定分别处理；异常发生后无法确定是否扣款时阻止玩家重试。数值精度超过经济插件支持范围直接拒绝，不静默舍入。

ProductionStore 在 data/player-data/<uuid>.json 保存平铺业务键（buy_limit.sword 是一个键，不是 JSON 路径），data/transactions 保存执行前的持久意图与最终状态；失败补偿收据在 data/recovery。限购/冷却复用插件根目录 playerdata/<uuid>.yml。文件 I/O 使用后台执行器和 AtomicFiles；条件只读已加载的不可变内存快照。

交易记录 pending/recoveryRequired 会跨重启阻止该玩家后续业务点击。记录提供核查依据，不保证跨经济插件、玩家存档和本插件文件的崩溃原子性，不执行自动退款或自动重试。关闭时取消引擎延迟并保存已有记录；onDisable 无法等待所有主线程补偿完成，因此不能宣称停服必定完成回滚。

当前命令按 player/op/console 身份分发，权限交给原版/目标插件；OP 仅同步调用期间临时授予并在 finally 恢复。旧允许列表已停用，不在本轮恢复。固定参数保留既有 JSON、中文、空格与选择器支持，动态输入仍经过安全 token/引用/控制字符校验；下文早期策略说明不代表当前生产限制。

含限购的购买按成功分支 give_item 总数量预留和提交，没有 give_item 时计一次操作；禁止随机发货与 return 后发货。仅冷却规则不限制随机动作。所有规则在同一个玩家文件中原子预留/提交，先检查所有额度再检查冷却。失败补偿完整时释放预留；状态已提交但消息/声音/命令失败仍消耗额度。

真实测试、示例和明确的未验证项见 PRODUCTION_PURCHASE.md。

## 实现与参考

入口接口为 `ActionEngine`，具体实现为 `TransactionalActionEngine`。参考 PROJECT_CONTEXT.md 中提供的 KaMenu 动作链、条件分支、数值输入引用和 return 示例，采用用户已确认的事务与等待规则。业务配置继续使用带 type 的 camelCase JSON POJO。

## 支持动作

| 动作 | 执行与回滚 |
| --- | --- |
| economy_deposit / economy_withdraw | 通过 Economy 接口变更，成功必须返回可补偿的 Change 收据 |
| player_data_set / player_data_increment | 通过 PlayerData 接口变更，收据恢复旧值或原先不存在的键 |
| give_item / take_item | 按物品类型与完整元数据匹配；先检查容量/数量，记录实际变更槽位，失败恢复这些槽位 |
| message | 提交后发送 Adventure 文本 |
| command | 提交后执行经过策略批准的玩家或控制台命令 |
| play_sound | 提交后播放资源键；兼容 Bukkit 静态声音名，正确保留 note_block 等名称中的下划线 |
| open_menu / close_menu / refresh_menu | 提交后打开、关闭或刷新菜单；打开和刷新通过 Menus 接口注入 |
| return | 正常结束当前链，提交此前状态动作及已收集的副作用 |

所有动作实现 ExecutableAction.execute()/rollback()。消息、任意命令、声音和菜单显示没有通用撤销能力：它们只在状态提交后执行，已尝试执行时 rollback() 明确拒绝，不伪造回滚成功。

## 执行流程

1. Scheduler.sync() 切回主线程，校验在线状态，复制菜单、按钮、动作与输入，按 UUID 排队。
2. 按钮有条件时调用注入的 Conditions；PASS 执行 actions，FAIL 执行 denyActions，ERROR 中止并拒绝执行分支。
3. 完整预校验动作链，最多 256 步。非法金额、材料、概率、延迟、命令等在状态变更前拒绝，包括 return 后和未命中概率的配置。
4. 可逆状态动作按配置中的相对顺序执行。delay 是非负整数 tick，当前动作等待且后续动作等待；chance 在 0–1 内，默认 1，未命中直接跳过。return 本身也遵守 chance 和 delay。
5. 状态阶段失败，已尝试的可逆动作按逆序调用 rollback()；一个补偿失败不会阻止其他补偿。
6. 状态成功后，将收集的副作用按其原相对顺序执行，每个副作用的 delay 在此阶段等待。副作用失败立即停止后续副作用，已提交的状态保持提交。

同一 UUID 的整条链串行；不同 UUID 可独立推进。异步接口完成后通过 Scheduler.sync() 继续，不在主线程等待磁盘或网络。接口内部异步实现同样必须遵守 Bukkit 主线程约束。

## 金额与命令

- 数值表达式仅支持十进制/科学计数法常量、数值型 `$(input)`、四则运算和括号，不执行脚本。使用有限 double，拒绝除零与溢出。
- 金额必须为正数，物品数量还必须为正整数；玩家数据 increment 可为负数或零。
- command 是固定的小写命令名，不包含斜杠、整行参数或动态占位符；arguments 是单独的 token 数组。executor 默认为 player，console 必须显式配置并由策略允许。
- 输入展开后仍校验单个 token，拒绝空白、换行、引号、分号等。CommandPolicy 必须根据命令、参数及执行身份决定是否允许；生产策略还应限制选择器、目标和数量，不能仅检查命令名。
- 命令策略在预校验和实际执行前各检查一次，等待期间撤销允许策略会阻止执行，结果为提交后失败。

## 回滚与恢复

经济和玩家数据适配器的契约：失败表示没有变更；成功必须返回准确的补偿收据。适配器应防止补偿覆盖其他插件或外部写入。物品回滚只恢复记录槽位，保留其他槽位；记录槽位被外部修改则拒绝覆盖并记录待处理补偿。

结果状态区分 COMMITTED、ROLLED_BACK、RECOVERY_REQUIRED、RECOVERY_SAVE_FAILED、POST_COMMIT_FAILED 与 REJECTED。失败补偿通过 RecoveryStore 保存；生产实现必须持久化后再完成 Future。本轮测试使用内存实现，不宣称已实现 Vault、业务数据文件或持久恢复日志适配器。

ActionChainResult.stateCommitted() 对 COMMITTED 与 POST_COMMIT_FAILED 返回 true。接入 LimitService 时，这两种结果均应消耗限购额度；恢复未完成的结果按 UNKNOWN 保留预留记录。

## 生命周期与接入范围

停用时先调用 shutdown()，取消延迟等待、等待在途接口完成并执行必要补偿，完成后再关闭 Scheduler。不能在主线程阻塞等待；接口必须最终完成。

异步排空要求插件仍启用且 Scheduler 可用；不能仅在服务器已进入 onDisable、调度已被禁用后开始异步排空。生产接入仍需设计停服期间在途事务持久恢复，并做真机验证；当前内存适配器测试不证明进程崩溃恢复或真实插件停用流程。

本轮提供完整引擎与可注入接口，遵循先接口和内存测试、后生产适配器的确认方案。DialogRenderer 支持异步引擎回调，但插件当前仍采用原有最小测试回调，未安装本事务引擎、未部署服务器 JAR。

## 验证（2026-10-10）

37 个 MockBukkit 动作引擎测试与 10 个表达式测试通过。覆盖所有要求的动作、各类状态补偿、逆序回滚、补偿失败继续恢复、恢复日志失败、容量与数量不足、背包外部修改冲突、延迟 return、概率跳过、UUID 排队、工作线程入口及异步完成、关闭时补偿、条件拒绝分支、命令参数防护和提交后失败。

审核补测覆盖异步补偿串行及主线程续执行、收据缺失标记待恢复、调用方取消不释放队列、延迟命令执行前重新校验策略。

完整 Gradle build 成功：139 项测试，136 项通过，3 项为既有 MockBukkit 原生 Dialog Provider 限制而跳过。

## 步骤 3.5：ClickGuard

ClickGuard 已接入 DialogRenderer，按 playerUUID + ":" + buttonId 原子加锁。处理中重复点击返回 false，不执行、不排队。按钮必须有非空 id；同一玩家跨菜单同 id 按钮共享锁。

异步动作链和回调后关闭菜单完成后，通过 try-finally 解锁；失败与同步抛错也释放。取消调用方返回的 Future 不会提前解锁实际仍在处理的点击。服务重建前应先等待旧事务结束，不提供强制清锁或提前超时释放。

7 个 ClickGuard 测试及 1 个 MockBukkit 回调集成测试通过；完整 build 成功，总计 147 项测试、144 项通过、3 项既有跳过。
