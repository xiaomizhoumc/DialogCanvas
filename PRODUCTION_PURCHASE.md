# 实际购买接入与测试

## 当前版本

- 移除网页“暂停单人游戏”，所有游戏 Dialog 均 pause=false。旧文件字段保留，true 只提醒不生效。
- 原空动作回调替换为生产条件、事务动作、限购、冷却及玩家数据适配器；保留主线程调度、点击去重、已有认证和 JSON/YAML 同步。
- 当前安装 EssentialsX 2.22.0、VaultUnlocked 2.20.2（插件名 Vault）、PlaceholderAPI 2.12.3。真实隔离服务端确认 Vault 经济提供者为 EssentialsX Economy。
- PAPI 条件用 `%vaultunlocked_eco_balance%`：当前 VaultUnlocked 自带的未格式化余额变量。旧 `%vault_eco_balance%` 需要另有 Vault 扩展，不能假定安装 PAPI 就可用。本轮没有新增标题/消息的 PAPI 替换。
- 可逆动作先提交；发货失败尝试退款。消息、声音、命令与显示菜单只在状态提交后执行。服务器插件仍不能保证进程崩溃时跨插件事务原子完成。

## 安装新 JAR 和示例

本轮只构建工作区 JAR，没有更新原测试服务器。

1. 服务端正常 stop，保存完成后，用 plugin/build/libs/DialogCanvas-0.1.0.jar 替换旧插件 JAR，确保 plugins 中只有一个 DCMenu JAR，再正常启动。
2. 控制台应输出“游戏动作已接入；Vault 经济提供者：EssentialsX Economy；PlaceholderAPI：true”。没有提供者时先检查经济插件，不进行购买。
3. 只复制 examples/shop_live_test.yml 到 plugins/DialogCanvas/menus/shop_live_test.yml。不手动同时复制 JSON；正式 JSON 和基线由插件生成。
4. 控制台执行 `dcmenu reload shop_live_test`，应显示重载成功；网页重新读取即可编辑。
5. 游戏内 OP 执行 `/dcmenu open shop_live_test`。

JSON 对照样例为 examples/shop_live_test.json，两者是相同业务文档；YAML 导入成功后规范化导出不保留手写注释，请保留 examples 原件。菜单中的命令按玩家/OP/控制台身份直接执行，由原版或目标插件检查权限；DCMenu 的命令允许列表已移除，旧配置不再影响执行。

## 原版客户端检查

请在测试服使用专门测试玩家；该示例会真实扣钱。

1. 游戏输入 `/papi parse me %vaultunlocked_eco_balance%`，应得到纯数字余额，不能仍显示原始 `%...%`。控制台通过 Essentials `eco give 玩家名 10000` 准备余额；用 `/balance` 核对实际余额。
2. 不勾选确认就点购买：不得扣款、发货、消耗额度。余额不足 1000 时也不得购买；余额不足所选数量总价时扣款阶段拒绝。
3. 首次成功购买前，先把背包填满再勾选确认购买 1 把：应购买失败，余额不减少，额度不消耗；然后腾出足够空间。
4. 选择数量 2 并确认：扣 2000，获得 2 把带显示光效的钻石剑；每天剩 1 把额度。快速连点只处理一次，成功后 10 秒内再次购买不扣钱。
5. 等待 10 秒后买 1 把：再扣 1000，累计获得 3 把。再等待后购买也应因每日额度用完而拒绝，余额不变。
6. 正常 stop/restart 后在同一天再试，额度仍应为已用完；次日以服务器时区零点重置。菜单关闭/刷新位置、按钮回调及闪光由原版客户端检查。

使用不同玩家单独验证余额不足；不要为了重复测试直接删除真实交易或玩家文件。生产账号请保留余额和物品核对记录。

## 待处理交易

异常、结果不明确、退款失败或停服中断会保留 data/transactions 的 pending/effectsPending/recoveryRequired 记录，阻止该玩家重复交易；限购文件可能留有 pendingQuantity。data/recovery 包含失败补偿信息，data/player-data 保存业务数据，插件根 playerdata 保存限购与冷却。

阶段 7.2 后，状态动作完成后必须先持久化限购计数/冷却，再保存 effectsPending 阶段，之后才能发送成功消息、执行命令/声音/菜单切换。必要写入失败停止副作用，保留待核查状态；不把已发生的经济/物品变化声称为全回滚。effectsPending 表示状态阶段已完成、最终副作用结果尚未确认，不是 completed。

副作用结束后才写最终收据并解除阻止；最终收据失败时命令/消息可能已经发生，保留阶段记录并阻止重复交易。恢复记录与交易意图使用同一 transactionId。限购/业务/交易/恢复文件的完整替换仍使用 AtomicFiles，4 MiB 写出上限与读取上限一致；写入失败不会发布新的玩家数据缓存。

先核对经济余额、玩家实际物品、交易配置/输入和限购记录，再处理具体待办；没有自动重试、自动退款或“清空全部记录”命令。不要仅删除一个日志就重新购买，以免再次扣款或绕过计数。

## 验证范围

- 自动验证：TypeScript、前端单元测试、完整 Gradle build；新增 MockBukkit 生产链路与 Vault 适配器测试，覆盖确认、变量失败、按数量限购、背包满退款、退款失败持久化阻止重试、玩家数据补偿、多个规则、冷却与运行时数量错误。
- 真实隔离 Paper 验证：Build 74 启动已安装的三种插件，实际经济服务发现、PAPI 内置扩展加载、meta 能力、商店配置校验、带注释 YAML 导入/JSON 一致、JAR 首页及正常关闭端口释放。报告：build-reports/production-startup-results.json。
- 未实际验证：原测试服务器部署后的玩家余额 PAPI 解析、原版客户端点击回传、真实玩家扣款/发货/退款及游戏显示。没有用 Bot 代替玩家检查。
- 参考 API：在线官方资料本轮访问返回 503；调用签名从实际 VaultUnlocked JAR 和固定 Paper API JAR 通过 javap 核对，并由真实 Paper 服务发现验证。未宣称在线文档查阅成功。

可复现隔离启动：构建后运行 `python scripts/verify-production-startup.py`。仅使用工作区 build/stage5-paper，读取并复制原服依赖 JAR，不停止、不写入原服务端。再次运行只复用完全相同且同步完成的示例，不覆盖已有 YAML；示例有手动修改或状态不明确时停止验证。

## 阶段 7.5：条件解析错误与消息错误

条件正常不满足是 FAIL，可执行 denyActions；占位符缺失、异常、未替换或非法数值是 ERROR，成功和拒绝动作均不执行，不扣款、不发货、不占额度。回执使用 conditionError 并记录 code/path；原子写成功才解除交易阻止，写失败保留 pending，重启继续阻止。不会根据旧 denied 记录猜测改写历史。

消息中的 PAPI 在状态提交后解析；失败返回 POST_COMMIT_FAILED，停止后续副作用，但保留已成功交易与额度，不能自动重试或宣称扣款已撤回。当前标题/正文/按钮文本不隐式接入 PAPI。完整规则及覆盖见 PLACEHOLDER_FAILURE_AUDIT.md。
