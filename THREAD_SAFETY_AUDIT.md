# 阶段 7.1：线程安全审计（2026-10-14）

## 范围与依据

已阅读 PROJECT_CONTEXT.md 最新密码修改、命令身份和生产交易记录，并追踪 Scheduler、DialogRenderer、SavedMenuOpener、ManagementCommand、MainMenuShortcut、ProductionActionEngine、TransactionalActionEngine、ConditionEngine、Vault/PAPI 适配器、ProductionStore、LimitService、认证、REST、菜单注册表及文件工具。

固定 Paper 26.1.2 Build 74，不升级依赖，不恢复命令允许列表。原测试服务器没有更新或控制；实际生命周期验证使用 build 内的新建独立实例，没有 Minecraft 客户端或 Bot。

**线程规则**：无 Async 后缀的 Future continuation 可能运行于完成上游的线程，也可能在注册它的线程内立即执行。因此只允许 continuation 直接处理纯数据；游戏操作明确经 Scheduler。已经在主线程的动作推进、补偿和同步事件不再叠加调度。

以下行号以本轮最终源码为准，路径均在 `E:/project/menu/plugin/src/main/java/dialogcanvas/` 下。

## 游戏状态入口审计表

| 文件与行号 | 入口 / 访问内容 | 线程依据 | 风险 | 修复结果 |
|---|---|---|---|---|
| DialogEditorPlugin.java:47 | onEnable、命令注册、MenuEnvironment.capture | Paper 生命周期主线程；能力捕获有主线程断言 | 配置文件 I/O 阻塞主线程 | 原配置读取/default 原子写入在主线程；移到 DCMenu-initialization，删除重复 reloadConfig |
| DialogEditorPlugin.java:82 | 配置发布、游戏适配器创建、事件注册 | 配置工作线程完成后显式 startupScheduler.sync | 关闭与启动回调竞争 | 使用捕获的 Scheduler、HTTP、动作实例，避免关闭后回调再读已清空字段 |
| DialogEditorPlugin.java:101 | Vault 服务注册/注销、经济提供者名称、PAPI 可用性 | 服务事件经 Scheduler.sync；初始化结果也显式 sync | 服务事件和 Future 线程不同 | 保留边界；HTTP 元数据只读取已发布的字符串/布尔值 |
| DialogEditorPlugin.java:159 | 生产 open/refresh 适配器 | 方法主线程断言；调用方是主线程动作、命令或同步事件 | 关闭后访问空资源 | 停止/尚未准备时返回异常 Future，不访问已清空资源 |
| DialogEditorPlugin.java:133、187 | onDisable、关闭入口 | Paper 生命周期主线程 | Paper 返回 onDisable 后关闭类加载器 | 主线程动作截止后，仅关闭期间有界等待后台 I/O/Jetty 收尾，服主已确认此例外，见关闭专项 |
| scheduler/Scheduler.java:28 | sync / syncAfter | 主线程零延迟直接执行；其他入口用 Bukkit runTask/runTaskLater（调度登记/任务取消是异步可用的基础设施，不是游戏状态操作） | 回调持调度锁阻挡异步登记 | 原直接执行持有调度锁，工作线程登记可被阻塞；现在用户代码在锁外运行 |
| scheduler/Scheduler.java:72 | close / 取消排队任务 | close 主线程断言；取消 Future 回调在锁外执行 | disabled 清理拒绝 / pending 未结束 | Paper 已将插件标为 disabled 时，仍允许 close 前的立即主线程清理；工作线程/延迟任务拒绝，close 后全部拒绝 |
| command/ManagementCommand.java:42 | BasicCommand.canUse | **Build 74 异步命令树线程也会调用** | 异步命令树读取权限 | 原直接 hasPermission 越界；公共命令根返回 true，实际子命令权限仍由主线程 executeOnMain 校验 |
| command/ManagementCommand.java:48 | suggest、权限及命令源读取 | 先检查主线程；异步调用返回空建议 | 异步建议读取 Bukkit 状态 | 不在工作线程读 CommandSourceStack/玩家权限，不同步阻塞等待主线程；主线程 Tab 行为保留 |
| command/ManagementCommand.java:68、74、166 | execute、管理权限、消息、菜单打开 | Scheduler.sync 后才读命令源及 sender | 文件回调直接发送消息的风险 | 保留管理权限；YAML reload 工作线程结束后通过 sync 回复，离线玩家不收消息 |
| dialog/MainMenuShortcut.java:29 | 潜行、交换副手、权限、UUID、取消事件 | PlayerSwapHandItemsEvent 原生同步事件，新增主线程断言 | 取消同步事件不能推迟 | 移除重复 sync，避免将事件修改推迟；完成回调仅移除 ConcurrentHashMap 中的 UUID |
| dialog/SavedMenuOpener.java:33 | 文件/注册表结果 → 在线检查 → display | repository Future 可在菜单工作线程完成；每次显示明确 Scheduler.sync | 显示失败被转为成功 / 玩家离线 | 原异常通知后正常完成，动作调用者误认为成功；现在通知后传播异常，离线显示也异常结束 |
| dialog/DialogRenderer.java:51、70、79 | show / render / create、Dialog/ItemStack/meta/输入/按钮构造 | show 显式 sync；render/create 主线程断言 | 未确认越界 | 无确认越界；POJO 复制不涉及 Bukkit；材料/声音注册表和 ItemStack 操作在主线程 |
| dialog/DialogRenderer.java:119、157 | 新增背包上限绑定 / getInventory / ItemStack 比较 | create 和 bindPlayerInputs 均主线程断言；只修改已复制的菜单快照 | 新增功能线程边界与编译错误 | 审计期间工作区新增，确认未越界；修复多余括号造成的编译错误，保留新增配置 |
| dialog/DialogRenderer.java:301 | 原生按钮回调、UUID/在线判断、动作、关闭 Dialog | 原生回调不假设线程；入口 sync，动作完成后 close 再显式 sync | 原生回调 / 事务完成线程不保证 | 不假设动作 Future 完成线程；ClickGuard 覆盖整条异步操作与关闭结果 |
| engine/production/ProductionActionEngine.java:48 | executeAsync、玩家状态、Dialog 输入、交易去重 | 首段 scheduler.sync；begin 在文件线程，run 显式 onMain | 调度拒绝导致去重和结果无法收尾 | 原最终处理依赖再次调度才能释放 active/完成结果；现在纯并发记账无条件收尾，失败异常传播 |
| engine/production/ProductionActionEngine.java:84、109、118、130 | 条件、限购反馈、动作结果消息、刷新菜单 | 每个有玩家访问的 continuation 单独 onMain | 多个 continuation 线程不能继承假设 | 数量/状态计算仅使用模型和原子引用；停止后不继续刷新，不向离线玩家执行动作 |
| engine/TransactionalActionEngine.java:54、71、122、137 | 两种执行入口、入队、条件/计划 | 入口 sync；后续队列、Run 状态、prepare 在主线程封闭访问 | 队列状态跨线程的风险 | 已有保证不再重复包装；模型/输入深拷贝后用于整条事务 |
| engine/TransactionalActionEngine.java:176、211、243、292 | 延迟推进、执行/回滚、异步适配器恢复 | 延迟 syncAfter；provider Future.whenComplete 都经 hop → sync | 迟到 receipt 恢复已停止动作 | isDone 后 join 只提取已完成结果，不等待；回调状态检查统一放在主线程，迟到回调不恢复已截止 Run |
| engine/TransactionalActionEngine.java:352 | 命令参数展开、player/op/console 分发 | prepare 和 effect 执行都在主线程 | 临时 OP 恢复和执行身份 | 保留三种身份与目标插件权限；临时 OP 在 finally 恢复原状态，无命令允许列表；命令是提交后的不可逆副作用 |
| engine/TransactionalActionEngine.java:427、480、524 | 背包读写、物品构建、声音键解析/注册表 | 由主线程 prepare/execute/rollback 调用 | 离线背包回滚不可靠 | 不在文件工作线程克隆/序列化 Bukkit ItemStack；同步补偿完成后才继续动作；玩家离线时不写可能已经保存/卸载的背包对象，生成待人工核查补偿记录 |
| engine/condition/ConditionEngine.java:50、169、207、210 | 权限、玩家 UUID、玩家数据、PAPI | evaluateDetailed 入口主线程断言；production 调用在 onMain | 求值必须同步 / 结构校验可异步 | validate/结构编译不访问玩家；求值帧每次独立，PAPI 失败不通过 |
| placeholder/PlaceholderApiResolver.java:30 | 可用性、PlaceholderAPI.setPlaceholders | resolveResult 先检查主线程，越界返回失败并 WARN | 占位符扩展读取游戏状态 | 无确认越界；不把 expansion 输出/玩家输入写入日志，失败默认不通过 |
| engine/production/VaultEconomyAdapter.java:16、19、30、44 | 服务查询、OfflinePlayer、余额、存取款、补偿 | provider/mutate/rollback 均主线程检查；动作恢复通过 hop | 经济和服务状态必须同步 | 无确认越界；提供者变化拒绝补偿并标记待核查，recoveryData 只返回纯数据 |
| engine/production/ProductionStore.java:102 | 补偿记录的 ItemStack.serializeAsBytes | 主线程断言；先构造纯 JSON/字节再 work 写文件 | ItemStack 序列化触发 Bukkit | 保留现有正确边界，不把 Bukkit 序列化移到工作线程 |
| engine/limit/LimitService.java:61、191 | permission → conditions → reserve → execution → finish | 权限/条件/执行 supplier 每次经 onMain；reserve/finish 经 worker | 限购文件线程调用玩家 supplier | UUID 队列 synchronized；不因为前一 Future 在主线程就直接访问玩家；UNKNOWN 保留预留 |
| web/menu/MenuEnvironment.java:11 | Material、Sound、RegistryAccess 能力扫描 | onEnable 主线程调用，capture 自带断言 | HTTP 扫描注册表的风险 | HTTP/验证工作线程复用不可变 Set，不调用 Bukkit 注册表 |


### 固定 Paper 命令线程证据

从原服务端 **只读**查看 Build 74 JAR，通过 Java 25 javap 导出：

- `build-reports/paper-commands-bytecode.txt`：Commands.sendCommands 将 sendAsync 提交 COMMAND_SENDING_POOL；sendAsync → fillUsableCommands → CommandNode.canUse。
- `build-reports/paper-command-bytecode.txt`：PaperCommands 注册 predicate 委托 BasicCommand.canUse；suggest provider 委托 BasicCommand.suggest。

这不是根据方法名字猜测线程。公共 `/dcmenu` 根现在可见，但缺少 dcmenu.admin / dcmenu.shortcut 的玩家仍不能执行受保护子命令；管理建议不向普通玩家泄露菜单 ID。

## 纯数据、文件与线程安全工具

| 文件与行号 | 入口 | 线程依据 | 结果 / 限制 |
|---|---|---|---|
| config/PluginConfig.java:35 | 配置读/default 写 | DCMenu-initialization 显式 executor | SafeConstructor 只读数据，不通过 Bukkit YAML 自动反序列化对象；配置只发布不可变 record |
| dialog/icon/VanillaIconResolver.java:23 | 原版图标目录读/不可变映射/Adventure 组件 | 启动工作线程提前加载图集；之后读取不可变 Map | 避免首次在游戏线程创建 renderer 时读取内置 JSON；构建文字/Sprite 组件本身无 Bukkit 状态 |
| dialog/TextRenderer.java:13、18 | TextSpec → Adventure Component | 纯解析，可在验证/渲染线程使用 | 固定版本序列化器，无玩家/PAPI/注册表访问；Mapper 配置完成后不修改 |
| engine/ClickGuard.java:17 | 去重/完成/取消 | ConcurrentHashMap，handle 的 finally 移除锁 | 返回 Future 的取消不提前释放仍在执行的操作；内部生产 Future 最终结束，锁随之释放 |
| engine/production/ProductionStore.java:31、48、52、66、77、127 | 初始化、意图、记账、玩家数据与回滚 | 单一 DCMenu-transactions 文件线程；缓存 CHM 中的快照只替换不原地改动 | find 返回 deepCopy；新增 closed 防止 initialize 迟到后重新宣称 ready；拒绝返回异常 Future |
| engine/limit/LimitService.java:88、122、199、204 | YAML 预留/计数、关闭 | 虚拟文件线程；每 UUID 串行；closeAsync 等待尾链后 shutdown | 调用者取消返回副本不会释放交易队列；结果不确定保留磁盘预留，主线程无等待 |
| web/auth/AuthService.java:69、116、160、245、263 | 初始化、BCrypt 登录/改密码、关闭 | 有界 DCMenu-auth 工作池；无 Bukkit | BCrypt/原子写在 worker；Map/同步块保护会话/哈希；close 可能等哈希提交锁，所以生命周期不能直接在主线程调用 |
| web/auth/AuthMiddleware.java:23 | Cookie/Origin/CSRF/session | Jetty HTTP 线程，纯字符串/内存校验 | 无玩家或 Bukkit 状态；错误响应/HTTP Future 回调允许在 HTTP/auth/菜单 worker 完成 |
| web/menu/MenuRoutes.java:17、61 | CRUD/validate/meta/HTTP 错误 | HTTP 入站 → Repository.submit → 文件 worker | 不从 HTTP 访问 Bukkit；上下文响应按 Javalin future 契约完成 |
| web/menu/MenuRepository.java:39、124、138、233、342、356 | 启动加载、读写/校验、删除、关闭 | 有界单 worker；closeAsync 独立等待 drain | 新增可观测的关闭 Future，先完成在途文件提交再 close FileStorage；工作线程等待，禁止 main join |
| web/menu/MenuRegistry.java:14、18 | 内存注册表发布/快照 | AtomicReference<State> + 不可变 Map/Entry/string | 这是项目注册表，不是 Bukkit/Paper 注册表，可从 HTTP/命令读取，无需 sync |
| web/menu/MenuValidator.java:34 | 类型/业务/引用校验 | 模型、TextRenderer、捕获的 MenuEnvironment、volatile 能力标识 | 不访问玩家、Vault/PAPI；实际经济可用性由主线程捕获后发布 |
| storage/FileStorage.java:20、83、106 | JSON/YAML/同步基线读写/关闭 | 生产调用来自 Repository worker；方法同步保护目录状态 | 构造器只计算 Path，**没有目录 I/O**；无需搬动构造器或重复 sync |
| storage/AtomicFiles.java:16 | 原子写工具 | 调用者为 config/auth/menu/transactions/limit 文件 worker | 本身同步文件 API，调用约束是 off-main；项目生产调用已追踪 |
| dialog/TestMenuService.java:43、54 | 旧示例初始化/读取 | 正式插件不创建、不注册该服务；load 自有虚拟线程 | initialize 是同步文件工具，仅测试/旧工具调用，不能接回主线程生产入口；未进行无关改造 |
| web/EmbeddedHttpServer.java:72、239 | HTTP 启停 | 启动 thenRunAsync / 失败 whenCompleteAsync；关闭工作线程，onDisable 有界等待 | 避免已完成 Future 导致 Jetty 启动落在注册回调线程；status 不暴露停止后的可用地址。Paper 安全关闭决策见下 |

## 关闭、取消与异常传播

1. Scheduler 在 close 前允许 disabled 生命周期里的立即主线程清理，拒绝 disabled 的排队/延迟任务；close 后全部拒绝并完成/取消 pending Future。
2. 动作 shutdown 首先取消等待并尝试当前可立即完成的补偿。不能等待的异步 provider 由主线程 abandonPending 截止：清空队列、结束当前/排队结果为 RECOVERY_REQUIRED，迟到 receipt 不再推进游戏状态。
3. 不在线玩家停止新动作；菜单查找期间离线不会显示 Dialog，Future 异常向调用者传播。已修改背包但在延迟期间离线时，不对离线旧 Player 库存对象写入并声称持久回滚；补偿记录保留前后槽位信息，结果为 RECOVERY_REQUIRED。
4. ProductionActionEngine 的 active/pending 收尾只使用并发集合，不依赖可被拒绝的 Bukkit 调度。store 不再在发起 shutdown 后立即关闭；等待内部请求和限购尾链结束后才关闭。
5. 退出时 unresolved intent / UNKNOWN 限购预留继续阻止重启后的重复交易。不能保证完成的经济/背包补偿不宣称成功，不自动重新扣款。
6. 调用方取消事务或 ClickGuard 返回副本，不中断已开始的不可逆/文件提交，不提前放开去重/UUID 串行队列。Renderer 直接排队 Future 的取消则由 Scheduler 取消对应任务。
7. 保存/补偿异常保留异常 Future；SavedMenuOpener 报错后也保持失败，不再把命令后的菜单显示失败转换为成功。
8. `executeStep/rollback` 的 join 前有 isDone 检查，属于取已完成结果；运行期间真正阻塞等待只允许文件/生命周期工作线程；服主已确认仅插件关闭期间允许主线程最多等待 30 秒。

### 关闭专项：实际 Paper 限制与已确认修复

首次真实隔离验证发现：onDisable 返回后 Paper 立即关闭插件 JAR 类加载器。仅把 Jetty.stop 放到后台会在 ServletHolder/BaseHolder 收尾时报 `zip file closed`，因此该异步关闭方案不能作为通过结果。

服主已明确允许“仅关闭期间有界等待，采用最小修复”。onDisable 先截止主线程游戏操作并 close Scheduler，再等待配置、菜单文件、交易/限购及 HTTP 工作线程收尾，总上限 30 秒；文件 I/O / BCrypt / Jetty 本身仍在 worker 上执行。

配置、菜单、交易与限购关闭 Future 均代表实际工作线程 drain 完成；HTTP 停止结果防止调用者取消跳过清理。等待结束后才允许 Paper 关闭 JAR 类加载器。超时/异常明确记为关闭未完成并记录 SEVERE，不承诺强制停服或损坏文件系统下全部收尾完成；未决交易保留待核查状态。该例外不用于 HTTP 请求、常规动作、命令或正常运行。

## 工作区并行改动说明

验证期间检测到工作区新增数值输入、背包上限和共享限购配置。保留这些改动并补查新增入口，不把这些功能记为本轮开发。修复 bindPlayerInputs 多余括号导致的编译错误，并保持未设置共享 scope 时原有限购 key 算法，避免既有计数被换一个身份读取。最终全量测试新增的商店素材示例还出现旧材质 CHAIN；根据固定目录修正为 IRON_CHAIN，保留菜单 ID，并更新该菜单同步摘要及生成器兼容规则。最终构建包含验证时工作区的这些已有改动。

## 验证记录

- 最新完整 `./gradlew.bat build --offline --no-daemon --console=plain -Dorg.gradle.jvmargs=-Dfile.encoding=GBK` 通过。
- Java 356 项，353 通过、3 项原有 native Provider 限制跳过，0 失败。新增 12 项：Scheduler 锁/disabled/cancel、异步权限钩子、事务截止/迟到 receipt/离线背包补偿、菜单失败传播、存储关闭/初始化竞争、HTTP stop 入口与端口、纯数据配置加载。
- 旧测试同步更新：公共 canUse 与真正执行权限分开断言；菜单查找错误/离线/服务不可用在提示后仍保持 Future 失败。
- MockBukkit 验证玩家/provider 操作主线程、worker receipt 恢复、OP finally 恢复、延迟退出、玩家离线；真实本机 Javalin 检查 stop 非阻塞入口、关闭后端口可重新绑定。
- `scripts/verify-thread-lifecycle.py` 创建全新 build 实例，使用固定 Build 74 和独立端口。启动、登录、HTTP 文件保存通过；首次停止发现上述实际类加载器问题；有界等待修复后 6 项真实生命周期检查通过：启动、登录、HTTP 保存一致性、stop 收尾/进程退出/端口释放、重启失效/菜单读取、第二次停止无 DCMenu 调度/关闭异常。最终记录见 build-reports/thread-lifecycle-results.json；实例 JAR 与最终构建 SHA-256 一致，1111 个前端资源与 dist 字节一致，摘要见 build-reports/thread-audit-artifact.json。
- Minecraft 客户端没有连接；真实 Vault/PAPI 购买、背包和第三方命令效果未重新实测。现有适配器调用线程经过代码追踪/MockBukkit 验证；第三方插件自身同步 I/O、阻塞行为和异步内部实现不由 DCMenu 控制。
- 原测试服务器 JAR 未部署。本文不把本机 HTTP 测试当作实际玩家效果验证。
