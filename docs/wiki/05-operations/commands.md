# 05.1 完整命令与权限

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 控制台命令不加 /，游戏命令加 /。

| 命令 | 权限/调用者 | 实际结果 |
| --- | --- | --- |
| `/dcmenu`、`help` | 按可用权限显示帮助 | 不给普通玩家泄露全部菜单 |
| `/dcmenu editor`、`edit` | admin 或 dcmenu.command.editor | 输出本机实际 Web 地址，不自动浏览器、不绕过登录 |
| `/dcmenu main` | dcmenu.shortcut 或管理员规则；游戏玩家 | 打开唯一正式主菜单 |
| `/dcmenu list [页码]` | admin 或 dcmenu.command.list | 已加载快照按 ID 排序，每页 10 个，不扫描磁盘 |
| `/dcmenu open <ID> [在线玩家]` | admin 或 dcmenu.command.open | 玩家缺目标时为自己；控制台必须给完整在线名 |
| `/dcmenu reload [ID]` | admin 或 dcmenu.command.reload | 单个/逐项批量 YAML 导入，不重载 config.yml |
| `dcmenu data dry-run [UUID]` | 控制台，admin/维护权限规则 | 不改状态的预演 |
| `dcmenu data archive [UUID]` | 同上 | 备份后归档合格条目 |
| `dcmenu data restore <备份UUID>` | 同上 | 精确冲突检查后恢复 |

根别名 `/dialogcanvas`；`/dme` 仅注册时无冲突才启用，冲突保留原插件命令。没有 give、test、icons、shop、menu 调试子命令；给物品是业务动作，不是管理子命令。

| 权限 | 默认 | 边界 |
| --- | --- | --- |
| dcmenu.admin | op | 兼容所有管理功能；不能被单一细分拒绝反向撤销 |
| dcmenu.shortcut | true | 仅普通主菜单命令/快捷键 |
| dcmenu.command.help | false | 查看帮助；已有细分权限也可见相关帮助 |
| dcmenu.command.editor | false | editor/edit 地址，仍要网页登录 |
| dcmenu.command.list | false | 列表 |
| dcmenu.command.open | false | 为自己或任意在线目标展示，不是只允许自己 |
| dcmenu.command.reload | false | YAML 导入 |
| dcmenu.command.data | false | 维护权限，仍不能绕过控制台限制 |

委派权限时不要同时授予 admin 再试图用细分节点限制。Tab 补全按权限显示子命令、ID、在线名；异步请求安全返回空，不保证所有客户端情况都有候选，不做文件 I/O。目标不在线拒绝，不排队给离线玩家。异步展示/导入完成后才报实际结果。

## 相关章节

[管理命令专题](../../../MANAGEMENT_COMMANDS.md) · [快捷入口](../04-features/navigation.md)

---

[上一章：04.8 安全归档、恢复与审计](../04-features/maintenance.md) · [下一章：05.2 插件配置完整表](configuration.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
