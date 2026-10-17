# 05.2 插件配置完整表

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 了解 config.yml 与菜单 YAML 不同。

配置权威键来自 PluginConfig、RetentionConfig 和随 JAR 的 config.yml。修改设置正常停服/启动；`reload` 不重读配置。保留已有哈希，不复制别人真实配置。

| 路径 | 类型、默认值 | 约束与含义 |
| --- | --- | --- |
| bind-address | 字符串 `'127.0.0.1'` | 只能精确回环地址，不支持 0.0.0.0/公网/localhost 替代配置 |
| port | 整数 8080 | 1～65535；占用时 HTTP 失败，不输出可用地址 |
| password-hash | 字符串 `''` | 空则首次安全生成密码并原子存 BCrypt；不填明文；已有格式/cost 验证不通过会拒绝认证初始化 |
| data-directory | 相对字符串 `'data'` | 解析后必须在插件目录内；拒绝路径逃逸/不安全链接 |
| player-data-retention.enabled | 布尔 false | 默认不自动执行 |
| player-data-retention.mode | 字符串 dry-run | 仅 dry-run/archive；archive 为有备份的真实变更 |
| player-data-retention.on-startup | 布尔 true | 生产服务就绪后，不是未加载状态抢先清理 |
| player-data-retention.daily | 布尔 true | 就绪后每 24 小时后台执行 |
| player-data-retention.expired-days | 整数 30 | 0～36500，计数/冷却均过期后的额外保留，不是离线天数 |
| player-data-retention.batch-size | 整数 20 | 1～200，分批后台处理 |

表中默认指首次生成模板值：已有配置的 port、bind-address、data-directory 必须保留，缺失不是自动回填；password-hash 缺失按空处理，retention 缺失使用默认。不要写 null 或错误类型以期获得菜单模型的空值语义，配置解析单独校验。旧 actions.allowed-*-commands 已取消，不再启用或增加命令白名单。审计队列/10 MiB 轮转是当前实现固定行为，不虚构可配键。

改端口后用 `dcmenu editor` 确认新地址；开发代理的目标端口要由开发者同步修改，不能放宽 Origin/CORS。当配置首次存哈希或改密重写时可能丢注释，保留配置模板副本。

## 相关章节

[安装目录](../01-start/installation.md) · [归档规则](../04-features/maintenance.md)

---

[上一章：05.1 完整命令与权限](commands.md) · [下一章：05.3 更新、备份、迁移与恢复](operations.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
