# 阶段 7.4：点击去重和生产动作入口审计

日期：2026-10-15。目标：Paper 26.1.2 Build 74、当前单实例插件。复用 ClickGuard、DialogRenderer、ProductionActionEngine、TransactionalActionEngine、ProductionStore 和 LimitService，不新增交易 Web API、不改变命令身份/权限约定。

## 结论与修复

生产源码只有一处 `DialogAction.customClick` 注册点，全部业务按钮共用 `DialogRenderer.callback`。未发现正式确认/拒绝按钮另行直调引擎的路径；发现并修复以下问题：

1. 原锁键只有玩家 UUID 与按钮 ID，不同菜单同名按钮误互斥。改为结构化三元组 `(UUID, menuId, buttonId)`，避免字符串分隔符碰撞。键不含打开会话、回调 UUID、菜单修订或输入，刷新/重开/改输入不能绕过原锁。旧三参数重载保留供旧集成使用，正式渲染器只用菜单感知的新重载。
2. 原回调只校验在线和玩家 UUID。新增每玩家最新打开会话、原 Player 对象身份、应用侧使用次数/有效期、正式菜单版本租约。新界面替换旧会话；退出、插件停止、已知关闭动作撤销会话；缺省/CLOSE 的 afterAction 在业务准入时撤销整个旧会话。
3. 正式版本租约来自 MenuRepository 已提交、不可变注册表，不读取 HTTP 草稿或主线程磁盘。绑定库存上限前校对完整源定义并捕获 Entry 身份；保存、导入/重载、删除重建或服务关闭后旧租约失效。无关菜单保存不撤销本菜单租约。
4. 底层事务引擎公开入口改为返回结果副本，避免调用方取消破坏内部完成信号。现有 ClickGuard 和生产引擎也保留内部结算 Future，外层取消不会提前解除原锁或玩家队列。
5. 生产入口增加同步初始化异常的 active/pending 收尾，避免进入玩家业务门后、异步链尚未接管就抛异常导致门永不释放。
6. `close_menu` 通过既有 Menus 适配器关闭并撤销已知会话；不另建菜单服务。纯关闭按钮直接关闭界面，不再经过业务引擎。

## 入口覆盖表

`custom` 包括缺省 actionType。业务步骤包括扣款/充值、发货/收物品、玩家数据、消息、命令、声音、打开/关闭/刷新菜单以及延迟/概率/return 组合。

| 入口/按钮槽位 | 注册或调用链 | 进入业务前的保护 | 结论 |
| --- | --- | --- | --- |
| NOTICE.noticeAction（custom） | create → button → customClick → callback | 主线程、身份、会话、次数/期限、正式版本 → ClickGuard | 覆盖完整 executeAsync |
| CONFIRMATION.yesButton | 同一 button/callback | 同上 | 确认按钮没有专用绕过链 |
| CONFIRMATION.noButton | 同一 button/callback | 同上 | “拒绝”标签不代表无副作用；配置的业务同样保护 |
| MULTI_ACTION.actions[] | 同一 button/callback | 同上 | 所有动作按钮使用同一保护 |
| MULTI_ACTION.exitAction（custom） | 同一 button/callback | 同上 | 退出槽位若配置业务，仍需保护 |
| 条件 FAIL 的 denyActions | guarded executeAsync → run → executeChain | 复用原点击锁和玩家级业务门 | 拒绝分支可能有状态变更，锁覆盖其完整结算；不另外递归调用按钮 |
| refreshAfterAction / open_menu / refresh_menu | 业务链内调用正式 Menus 适配器 | 原锁等待显示/刷新 Future 与最终交易记录 | 新会话不生成新的逻辑锁键 |
| close_menu 业务步骤 | executeChain → Menus.close | 属于原业务链，仍被原锁覆盖 | 关闭会话不取消交易或提前释放锁 |
| actionType=close 的任意槽位 | customClick → 身份/会话/期限校验 → closeDialog | 不调用业务引擎，不写交易或额度 | 仅界面操作，不需要业务 ClickGuard；不会执行误配置的 actions/denyActions（保留既有语义） |
| actionType=none / NOTICE 缺省原生关闭 / Esc | none 不注册业务回调；其余原生界面行为 | 不进入业务引擎 | 无业务副作用，不需交易去重 |
| 旧 TestMenuService 示例经同一 renderer 显示 | 文件加载 → renderer → 同一 button/callback | 同一会话/身份/期限及 ClickGuard | 该服务当前没有生产创建/命令注册点；无另行执行购买的测试按钮 |
| 测试用 NoOpActionEngine | 空实现，未安装生产 | 无业务 | 不提供生产购买路径 |
| 单元测试直接调用引擎 / executeChain | 可信服务端 API，不是玩家回调或网络端点 | 生产引擎玩家门，事务引擎/LimitService UUID 串行 | 无伪造 Paper 点击的外部入口；直接 API 不声称自带 GUI 会话校验或 ClickGuard |
| /dcmenu open、main、快捷换手入口 | ManagementCommand / MainMenuShortcut → SavedMenuOpener → renderer.show | 原有命令/快捷权限，正式保存定义；之后的按钮走上表 | 打开界面本身不扣款/发货，不为其获取交易锁；快捷键保留原有 pending 门 |
| Web CRUD/reload | 原有认证/CSRF/文件队列 → 注册表发布 | 不存在 Web 购买调用 | 发布新正式版本使旧按钮失效；不把管理写入混为游戏点击 |

## 点击者、会话和版本边界

- 回调排队到主线程后再检查：audience 必须是原 Player 对象、在线、对应最新 Session。UUID 相同但重连后的另一个 Player 对象也不能借旧回调执行。
- 每个按钮按传给 Paper 的同一 Options 校验 uses 与 lifetime，等于到期时刻即拒绝。默认单次使用，默认期限沿用固定 Adventure 的 DEFAULT_LIFETIME；显式配置不修改。应用计数在准入后扣减，重复被锁拒绝不占本地次数；Paper 自身可能按收到的点击尝试消耗传输层 uses，不保证重复包不消耗原生回调次数。这与购买额度不同。
- 正式插件构造 renderer 时强制接入仓库版本租约，拒绝未提交或已改动的定义。三参数独立 renderer 构造器仍供可信测试/集成使用：没有正式仓库就没有“实时正式修订”可核对，不将该模式算作正式版本验证。
- 版本校验是业务准入时的读取点。已经接受的交易继续使用被冻结的定义，不因之后编辑菜单或切换界面中途换价/改动作。菜单热编辑不是撤回已准入交易的手段。
- 运行中直接手改 YAML 不等于发布新正式版本；正常保存或 `reload` 后才改变正式注册表。遵循既有文件同步协议，不增加主线程文件读取。
- 固定 API JAR 未提供原生 Dialog 的 Esc/任意外部插件关闭通知。不能证明客户端此刻仍显示该屏幕；未知原生关闭不自动撤销 NONE/WAIT_FOR_RESPONSE 的应用会话，仍受次数、期限、后续显示、退出及正式版本约束。缺省/CLOSE、插件已知关闭和 close_menu 已显式撤销。第三方主动关闭集成可调用 renderer.invalidate(player)。不把“拥有有效回调”写成“已证明屏幕仍打开”。
- `render` 返回原生 Dialog 的可信集成入口会激活新会话；插件不知道调用方何时真正显示返回对象。正式游戏路径使用 show，外部集成需要遵循这一契约。

## 锁生命周期与异常

ClickGuard 在调用 supplier 前以 putIfAbsent 获取所有权，只由同一个 owner 删除。锁覆盖生产入口的完整 Future：意图落盘 → 条件/拒绝分支 → 限购预留 → 状态动作/延迟/异步回执 → 补偿或限购提交 → effectsPending → 提交后副作用 → refreshAfterAction → 最终交易记录与结果结算。

| 情况 | 锁及业务处理 |
| --- | --- |
| 同步抛异常、返回 null Future | finally 释放所有权；失败仍向外传播。同步 Error 也不遗留锁 |
| 正常完成/异步失败 | settlement handler 结束时释放；失败不被转换成购买成功 |
| 外层 ClickGuard Future 被取消 | 仅取消调用者视图；原 handler 继续等待业务，不能用取消开启第二笔 |
| 生产/事务 API 的调用方取消 | 不取消内部工作/队列；生产 active、底层 UUID 队列继续等待 |
| supplier 的底层 Future 本身终止/取消 | guard 将它视为终态并释放；这不是证明任意第三方脱离 Future 的后台业务已停止。内置生产 renderer 不向点击者暴露这一内部 Future；适配器必须提供真实完成/补偿信号，禁止自行 fire-and-forget |
| 点击之前离线/已过期/错误玩家 | 准入前拒绝，不获取锁、不创建意图、不占额度 |
| 准入后离线/界面关闭/重开 | 不因撤销会话直接释放锁。引擎停止推进离线玩家状态并处理补偿；离线背包持久性不确定则保留恢复记录 |
| 插件关闭 | 先关闭 renderer，撤销会话、禁止新准入。生产引擎截止新业务和延迟动作；未能等待的 provider 交给 RECOVERY_REQUIRED 终态/持久阻断，迟到回执不再推进游戏链。锁随终态结算释放，绝不清空后继续接受新交易 |

停服时的 RECOVERY_REQUIRED 是“交由恢复核查”的终态，不是确认第三方经济插件的后台调用必定没有变化。插件不能取消第三方已开始的不可逆写入。保留阶段 7.1 已批准的仅 onDisable 最多 30 秒有界等待；正常游戏操作不增加阻塞等待。

## 去重、串行、限购与恢复各自的保证

| 层 | 提供 | 不提供 |
| --- | --- | --- |
| Session/Options/正式版本租约 | 限定谁、哪次界面、哪个正式版本、何时还可发起业务 | 不证明客户端屏幕仍打开，不撤销已准入事务 |
| ClickGuard | 同进程、同玩家/菜单/按钮的执行期间只准入一次 | 不同按钮的互斥、完成后的防连购、持久请求身份、跨重启 exactly-once |
| ProductionActionEngine.active | 同玩家只接受一条生产业务；其他按钮/菜单/新 renderer 不能绕过 | 不把被拒的重复点击排队成下一笔购买；不是持久锁 |
| TransactionalActionEngine / LimitService | 每 UUID 串行动作链/预留流程；调用者取消不提前放行 | 不替代 callback 身份验证，不承诺多实例共享目录安全 |
| LimitService 持久预留与计数 | 原子预留全部规则；成功提交计数/冷却，明确回滚释放，不确定保留 | 不推断经济插件是否已扣款，不自动重放或无条件退款 |
| ProductionStore 的 pending/effectsPending/recoveryRequired 等未决记录 | 运行中和重启后阻止该玩家重新交易，提供人工核查依据 | 不把跨插件/文件的动作做成 ACID，不自动完成补偿，不把历史已成功点击永久判重 |

因此，操作完成后再次点击仍可能是一笔合法新交易，应由配置的冷却/限购控制；跨会话本身不代表必须永久拒绝再次购买。当前没有持久的客户端请求幂等键，不宣称整个交易系统“恰好执行一次”。不同玩家没有同一把点击锁，可独立推进；仍共用主线程和现有文件工作队列，不承诺完全无共享 I/O 延迟。

命令执行保持 player/op/console，默认玩家；权限由原版/目标插件判断。OP 只在同步分发期间临时设置并在 finally 恢复。动态输入、安全引用/控制字符及提交后命令语义保留，不恢复命令允许列表，不扩大调用身份。

## 验证与产物

- 完整 Gradle build 通过：440 项 Java 测试，437 通过、3 项既有原生 Dialog Provider 限制跳过、0 失败/错误。相比阶段 7.3 新增 27 个测试用例。
- 重点套件共 160 项（含 3 跳过）：ClickGuard 9、DialogCallbackAudit 17、DialogRenderer 13、ProductionActionEngine 26、TransactionalActionEngine 59、LimitService 25、MenuRepository 11。
- 扣款等待期间重复 20 次不再次进入业务，且只有一个交易意图/一次预留；最终回执写入故意挂起时已经发货和提交额度，但锁仍不释放；结算后才放行。
- 验证外层取消、新去重器、不同菜单同名按钮、刷新后新会话、旧会话、错误/同 UUID 不同 Player、精确到期边界、准入次数、同步/异步错误、拒绝分支副作用、停服迟到回执及重启阻断。另一玩家在第一人经济 Future 未结束时可完成购买。
- 菜单版本租约验证覆盖草稿不匹配、更新、删除重建、reload 和仓库关闭。回调入队后再失效也不能执行。
- 独立 Paper Build 74 的启动/HTTP 保存/停止端口释放/重启会话失效等 6 项生命周期检查通过，使用本轮同 SHA-256 JAR；该检查没有 Minecraft 客户端或经济插件交易。旧阶段 7.1 报告未覆盖，新增报告前缀 click-entry-lifecycle。
- 本轮没有改前端逻辑；JAR 内 1111 个前端资源与当前 frontend/dist 逐字节一致。未更新旧商店 ZIP 或 dist 下历史 JAR。
- 未部署原服务器、未连接 Minecraft 客户端，未进行真实 Vault/PAPI/第三方命令购买实测。原生槽位接线由生产源码审计，回调/交易行为由 provider-independent MockBukkit 测试覆盖；不把 3 项跳过或生命周期 HTTP 检查说成原生客户端点击实测。

结构化结果：`build-reports/click-entry-audit-results.json`；生命周期结果：`build-reports/click-entry-lifecycle-results.json`。本轮 JAR：`plugin/build/libs/DialogCanvas-0.1.0.jar`。
