# 阶段 8.5：菜单管理命令

## 用法

控制台省略开头的 `/`。根命令仍是 `/dcmenu`，保留 `/dialogcanvas`；`/dme` 仅在注册时未被其他命令占用的情况下加入。全部别名进入同一份 ManagementCommand，不改变登录、菜单版本、点击保护或生产动作权限。

| 命令 | 实际行为 |
| --- | --- |
| `/dcmenu`、`/dcmenu help` | 中文帮助；只列调用者有权使用的命令。普通快捷用户仍只看到主菜单用法 |
| `/dcmenu editor`、`/dcmenu edit` | 只输出服务器电脑的实际本机编辑器地址；不启动浏览器，不附带密码/Token，不绕过网页登录 |
| `/dcmenu main` | 在游戏内为自己打开正式主菜单，沿用 dcmenu.shortcut / 管理员规则和 Shift＋F 快捷入口 |
| `/dcmenu list [页码]` | 列出当前已加载的正式菜单 ID，按 ID 排序，每页 10 项，默认第 1 页；不扫描文件、不导入 YAML、不展示未保存草稿 |
| `/dcmenu open <菜单ID>` | 在游戏内为自己打开菜单；控制台必须补在线玩家 |
| `/dcmenu open <菜单ID> <在线玩家>` | 为指定在线玩家展示菜单，控制台和已授权玩家都可使用；使用完整玩家名，不做前缀匹配或离线排队 |
| `/dcmenu reload [菜单ID]` | 原手动 YAML 导入；省略 ID 则逐项批量导入，保留部分成功语义，不重载 config.yml |
| `dcmenu data dry-run [玩家UUID]` | 原只检查维护，仍仅服务器控制台 |
| `dcmenu data archive [玩家UUID]`、`restore <备份UUID>` | 原安全归档与恢复，仍仅服务器控制台；保护规则、备份和冲突不变 |

本项目原来没有 give 子命令，本阶段不新增 give、菜单道具或物品发放命令。业务动作中的 give_item 保持原有意义，与管理命令无关。旧 test/icons/shop/menu 调试子命令继续不可用。

### 示例

```text
dcmenu list
dcmenu list 2
dcmenu open weapon_shop Steve
dme edit
dcmenu reload weapon_shop
```

游戏内 `/dcmenu open weapon_shop` 为自己打开；`/dcmenu open weapon_shop Steve` 为 Steve 打开。菜单 ID 以 list 中正式菜单为准，不能填写显示标题。`edit` 和 `editor` 只能在 Web 已成功绑定后输出可用地址，端口失败不伪造地址。地址中的 127.0.0.1 指服务器电脑，不是另一台玩家电脑。

列表没有菜单时明确提示；非正整数、超大数字、超出当前页数的页码拒绝，不偷偷跳到其他页。分页对每次调用时的已加载快照生效；菜单集合在两次命令间变化时，页数和排序也可能变化。

## 权限

| 权限 | 默认 | 作用 |
| --- | --- | --- |
| dcmenu.admin | op | 继续允许所有管理子命令，兼容已有管理员；不要求再授予细分节点 |
| dcmenu.shortcut | true | 原主菜单命令及 Shift＋F；普通玩家不因此获得列表或其他菜单补全 |
| dcmenu.command.help | false | 单独查看帮助，不授予其他命令；有其他细分权限的用户也可查看相应帮助 |
| dcmenu.command.editor | false | editor 和 edit 共用权限，仅显示地址 |
| dcmenu.command.list | false | 列出正式菜单 ID |
| dcmenu.command.open | false | 为自己或任意在线玩家展示指定正式菜单；授权时请按此完整范围理解 |
| dcmenu.command.reload | false | 手动导入一个或全部 YAML |
| dcmenu.command.data | false | 维护命令权限，但**仍不能绕过服务器控制台限制** |

未设置细分节点时，管理功能默认仅管理员可用。`dcmenu.admin` 是全权兼容入口，细分节点的单独拒绝不会撤销已有 admin 权限；如需限制某个用户，应不给 admin，再按需授权具体节点。不新增 dialogeditor.* 命名，不为 main 创建另一套权限；玩家使用 Shift＋F 的条件不改。权限组插件的通配符语义仍由权限插件处理，本插件逐项检查具体节点。

只有快捷权限的玩家补全仍仅提供 main，显式输入 help 可看到主菜单用法。已获细分管理权限的用户，帮助及补全只显示相应功能；未获 open/reload 权限不会看到相应菜单 ID，未获 open 权限不会得到目标玩家补全。读取 Web 编辑器地址不等于得到会话权限。

## 别名冲突

JavaPlugin.registerCommand 在固定 Paper 中本身委托 COMMANDS 生命周期。本阶段仍使用同一生命周期和 BasicCommand，在**实际注册时**检查 Brigadier 根节点和 Bukkit CommandMap 是否已有 dme，然后把可用别名加入 dcmenu 的同一次注册。

发现冲突只输出中文诊断并跳过 `/dme`，不注销、不替换、不强制接管其他插件命令；`/dcmenu`、原 `/dialogcanvas` 照常注册。检查覆盖注册时已存在的命令，不承诺控制后加载插件的自行覆盖行为。不存在用第二个独立执行器或 plugin.yml 命令重建管理系统的情况。

## 线程、打开流程与审计

- execute 复制参数后通过现有 Scheduler.sync 进入主线程；检查权限、查完整在线玩家名、列表输出及命令注册均在主线程。列表只使用 MenuRepository.completionIds 的已提交不可变注册表，补全不扫描磁盘。
- canUse 继续不读玩家权限，避免异步命令树构建触发 Bukkit 状态访问。suggest 先检查线程和有效来源；异步或未知来源直接返回空结果，绝不在异步线程读取权限/在线玩家。主线程补全按权限提供子命令、菜单 ID、在线玩家名、页码和维护模式。
- 展示继续复用 `openSavedMenu → SavedMenuOpener → DialogRenderer`：后台取正式定义、主线程检查玩家状态/showDialog、现有版本 lease、回调会话及 ClickGuard 保持。没有绕过条件、交易、权限或限购的特殊渲染路径。
- 控制台或为其他玩家打开时，真实 display Future 完成后才反馈“已展示”；无提前成功。未来异常、同步异常、目标中途离线和取消有失败提示，不自动重试。为自己打开保留原打开器的反馈，不重复刷一遍错误。主线程调度被停止时可能无法再向玩家发送消息，审计记录 SCHEDULING_REJECTED / PLUGIN_STOPPING / OPERATION_CANCELLED 等实际失败，不能算展示成功。
- 审计继续复用 AuditLogger，`edit` 规范为 `dcmenu:editor`，根别名也仍使用 dcmenu 的固定事件目标；list 成功/空结果、非法页码、缺目标、离线目标、服务未就绪及实际异步完成均有结果。命令参数和玩家输入不写入审计日志；正常控制台反馈可包含菜单 ID 和玩家名。

## 验证

ManagementCommandTest 覆盖控制台与玩家、21 个菜单分页/未加载 YAML、页码错误、完整在线目标/离线拒绝、edit、细分授权/默认权限/无泄露、别名冲突策略、主线程及异步补全、参数快照、真实完成前无成功消息/审计、中途离线与取消、服务未就绪；原 SavedMenuOpenerTest 和 MainMenuShortcutTest 保持回归。

```powershell
./gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
python scripts/verify-commands-paper.py
python scripts/verify-commands-artifacts.py
```

隔离 Paper 脚本在工作区 build/commands-paper-* 创建临时实例，以实际控制台验证注册、分页、原别名、dme/edit、离线拒绝、日志及正常停服。第三次启动加入仅用于隔离验证的 DmeConflictFixture 插件，确认 dme 保留给原命令且 dcmenu/dialogcanvas 可用。没有连接 Bot 或游戏客户端；在线玩家展示、委派权限和补全由 MockBukkit 验证，仍需服主用实际客户端复核。原服务器 JAR 和数据未修改；实际构建统计和证据见本阶段最终记录。

### 最终结果（2026-10-16）

- 完整 Gradle build 成功，593 项 Java 测试：589 通过、4 项既有跳过、0 失败/错误；ManagementCommandTest 为 27 项。跳过分别为 MockBukkit 未提供的 3 类原生 Dialog 构造，以及原有完整自定义剑默认属性测试，不计为通过。
- 最终同一 JAR 的隔离 Paper 26.1.2 Build 74 三次启动/停止共 15 项检查通过。测试夹具首次编译缺少 Bungee API 类型，补齐夹具 classpath 后完整重跑通过；没有修改生产依赖来适配夹具。
- 1050 个前端资源与 JAR 逐字节一致；前端未改，不额外重跑 TypeScript/前端测试。导出 `dist/DialogCanvas-0.1.0.jar`，SHA-256：`2180397ebdecf315a533dc635184ce189d8f51850ba05d8a67f451a6fa2d8126`。
- 证据：`build-reports/commands-acceptance-results.json`、`commands-paper-results.json`、`commands-paper.log`。原服务器未部署、数据未改；没有连接实际客户端。

### 人工复核

1. 停服后替换 JAR，启动；控制台输入 `dcmenu list`、`dme edit`，确认实际地址和列表；若其他插件已占用 dme，应保留其命令并提示跳过。
2. 管理员在游戏内输入 `/dcmenu open <已有菜单ID>`；控制台输入 `dcmenu open <已有菜单ID> <自己的完整在线名>`，确认收到原有 Dialog、按钮仍可正常点击。再指定离线名字，应拒绝。
3. 普通玩家输入 `/dcmenu main` 或 Shift＋F，确认原规则；Tab 不应列出其他菜单。通过实际权限插件只委派 `dcmenu.command.list`，应只能查看列表和帮助，不能打开其他菜单或导入 YAML。
4. 为管理员 Tab 补全子命令、菜单 ID、在线名；若该服务端从异步线程请求补全，本版按既有线程审计规则返回空结果，避免异步访问 Bukkit。此行为与权限拒绝不同，不承诺所有客户端请求均展示候选。
