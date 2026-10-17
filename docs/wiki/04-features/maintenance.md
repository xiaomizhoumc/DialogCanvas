# 04.8 安全归档、恢复与审计

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 有控制台权限，已做完整备份。

## 先 dry-run

控制台 `dcmenu data dry-run`，或加玩家 UUID 缩小范围。它只解释候选/跳过原因及统计，不写玩家文件、不创建备份。不是按 mtime/离线 N 天删除；没有可靠时间/规则证明的数据保守跳过。

永久限购、有效计数/冷却、pending 预留、未决/未知交易、恢复记录、业务 JSON 均保留。首版只归档能证明已过期并过保留天数的限额条目，不删除整个 YAML。维护与交易准入、UUID 队列、当前菜单规则及缓存协调，维护时同玩家点击可能暂拒绝。

## 明确执行与恢复

核对预演后可用控制台 `dcmenu data archive [玩家UUID]`，每项执行前重新检查，备份失败不清理。备份在 data/retention-backups/<备份UUID>/record.json，独占新版本，不覆盖旧备份。恢复用 `dcmenu data restore <备份UUID>`，验证摘要和精确 before/after；有新交易/其他修改拒绝，不合并、不强制。恢复前也保留安全备份。

默认自动任务 disabled；配置启用后默认 dry-run，可启动就绪后及每 24 小时后台分批。不是每天固定本地零点任务。备份和交易恢复记录不会自动清理，不能把它们当日志垃圾。

## 审计

插件目录 logs/audit.log 是 UTF-8 JSON Lines，每行一个 JSON 对象；记录菜单创建/更新/删除、YAML 导入、维护、密码修改及管理命令实际结果。普通读取、浏览器只读转换、每次文字输入/临时预览不记；生产交易继续原账本。

字段有时间、事件类型、operationId、source/actor/target/result/code，按需 revision；Web 标记 local-admin-session，不记录 Token。密码/哈希/Cookie/CSRF/完整正文/命令参数/变量结果不得进入审计。后台有界队列、单写线程、10 MiB 轮转、唯一文件名，不覆盖历史。

写日志失败会告警并可观察，已提交业务不会伪装回滚或自动重试；停服先等生产者收尾再有界排空。不是防篡改账本，也不保证断电零丢失。

## 相关章节

[保留规则专题](../../../PLAYER_DATA_RETENTION.md) · [审计专题](../../../AUDIT_LOGGING.md)

---

[上一章：04.7 玩家数据、限购、冷却与事务](data-transactions.md) · [下一章：05.1 完整命令与权限](../05-operations/commands.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
