# 玩家限购与冷却

## 当前接入（2026-10-15）

生产动作引擎已接入 LimitService。ClickGuard 负责同玩家、同菜单、同按钮执行期间去重；生产入口按玩家拒绝并发，LimitService 按 UUID 串行持久预留/计数。刷新不会重置这些保护，取消外层 Future 不会提前释放队列；结果不确定仍保留预留并拒绝重试，不将点击去重当成持久交易幂等。详见 CLICK_ENTRY_AUDIT.md。下文“本轮不接入”是早期实现阶段的历史范围。

## 范围

`LimitService` 提供可注入的完整检查与执行入口；本轮不接入线上按钮、经济或菜单适配器。动作引擎执行与回滚测试已在步骤 3.3 完成，详见 ACTION_ENGINE.md。

限购状态独立存放在插件目录下的 `playerdata/<uuid>.yml`。原有通用玩家业务数据接口保持不变。限购键建议使用 `菜单ID/按钮ID/限购项名称`，不以玩家输入生成路径。

## 规则

- 数量按购买物品数量计数；例如每日限购三把剑，购买两把消耗两次额度。
- `maxCount=-1` 表示无限购；`0` 禁止购买；数量必须是正整数。
- DAILY：服务器当地次日零点；WEEKLY：下一个周一零点；MONTHLY：下月一日零点。
- NEVER：不重置；TIMED：第一次成功执行时起算，`timedSeconds` 秒后重置，下一次成功执行开启新周期。
- 使用服务器 `ZoneId.systemDefault()`；测试可注入 Clock 和 ZoneId。日历周期按当地日期计算，支持夏令时。
- 时区在创建服务时固定；更改服务器时区后需重建服务，已有记录的 resetAt 是绝对时刻，不会自动重算，需安排状态迁移。
- 冷却以秒配置，成功提交时开始，跨重启保留，与限购重置独立。
- 权限 → 条件 → 限购 → 冷却 → 执行。权限、条件和执行入口通过 Scheduler.sync() 调用；文件读写在工作线程进行。
- 同一 UUID 的整个流程串行，不同 UUID 可以并行；调用方取消返回 Future 不会释放尚在执行的请求。

## 原子写入与失败处理

通过 AtomicFiles 写同目录临时文件、force、原子替换，不降级为非原子覆盖。SnakeYAML 使用安全构造器读取，拒绝重复键；私有依赖随 JAR 打包并重定位。

原子替换保证文件不会暴露部分内容；当前实现不对目录元数据执行 fsync，不承诺硬件断电时最近一次替换一定持久。时间间隔溢出在执行前校验，防止购买成功后才发现无法计算过期时间。

执行前先持久化 pendingQuantity/pendingSince；明确 COMMITTED 才累计数量并启动冷却，明确 ROLLED_BACK 则撤销预留。异常、UNKNOWN、提交后写盘失败或进程中断保留预留，下次请求拒绝并等待核查。不会猜测交易是否成功。管理员需要在停服或服务关闭后依据实际交易结果处理待确认记录；本轮没有自动恢复命令。

动作引擎的 POST_COMMIT_FAILED 表示可逆状态已提交，调用适配器应映射为 COMMITTED，避免消息或声音失败释放购买额度。RECOVERY_REQUIRED/RECOVERY_SAVE_FAILED 应映射为 UNKNOWN。

`execute()` 返回结构化拒绝原因；文件损坏或 I/O 失败返回异常，由调用方记录并提示。修改既有限购项重置配置前应迁移状态或采用新键。

## 生命周期

目录由单个 LimitService 实例独占。关闭时先拒绝新请求，用 closeAsync() 等待已排队请求结束，再关闭 Scheduler。不能在服务器主线程阻塞等待该 Future；执行适配器必须最终完成。close() 仅发起关闭。

## 接口配置

Policy 包含 key、maxCount、quantity、reset、timedSeconds、cooldownSeconds。模型 LimitDefinition 新增 timedSeconds；已有 cooldown 映射值按秒传入。权限与条件以 Supplier<Boolean> 注入，条件求值 ERROR 应转换为 false。执行适配器返回 CompletableFuture<Outcome>。

## 审核验证（2026-10-10）

23 个 LimitService MockBukkit 测试与 2 个 AtomicFiles 测试通过，完整构建成功。补测：重置时刻前一纳秒与恰好到期、周一开始新周、闰年二月、十二月跨年、夏令时 23/25 小时日、TIMED 首次成功起算且后续购买不延长周期、延迟购买跨零点按提交周期计数、极大间隔执行前拒绝、原子替换成功和失败清理。
