# 阶段 8.6：P1 体验提升联合验收

验收日期：2026-10-16。版本：DialogCanvas 0.1.0，Paper 26.1.2 Build 74，Java 25。

## 结论

**自动测试、浏览器交互及隔离 Paper 联合验收通过；原版客户端复核待进行。**

覆盖阶段 8.1–8.5，以及阶段 7 的线程、持久化、认证、点击保护和占位符回归。本轮未发现确认的新增生产缺陷，因此没有修改生产代码或扩大功能范围。新增联合验证和证据收集脚本，更新使用说明及契约说明。未部署原服务器 JAR，未读取或清理原服务器玩家数据。

| 验证层次 | 本轮结果 | 实际范围 |
| --- | --- | --- |
| TypeScript | 通过，退出码 0 | 当前前端全部类型检查 |
| 前端自动测试 | 156 通过，0 失败、0 跳过 | 实际 Vitest 报告，不沿用历史统计 |
| 前端构建 | 通过，退出码 0 | 既有锁定依赖和 Vite 生产构建 |
| 完整 Gradle | 通过，8 项任务全部重新执行 | `build --rerun-tasks`；593 项 Java 测试，589 通过、4 项既有跳过，0 失败/错误 |
| 独立 Edge 浏览器 | 62 项通过 | 当前生产 dist + 本机模拟 REST；4 个独立浏览器用例集，无脚本异常 |
| 隔离 Paper 联合流程 | 19 项通过 | 工作区新建实例，3 次正常启动/停止；保留、备份恢复、审计、命令及别名冲突 |
| 隔离 Paper 阶段 7 回归 | 13 项通过 | 另一个工作区新建实例，2 次正常启动/停止；实际 HTTP、文件、重启和端口 |
| 开发代理 | 4 项通过 | 临时 Vite 与临时后端，异源及缺 Origin 的写入被拦截，本机合法请求正常 |
| 单 JAR | 通过 | 1050 个前端资源逐字节匹配，默认配置无密码哈希，未打入测试类/PlaceholderAPI |
| 原版客户端 | **本轮未验证** | 未连接游戏客户端或 Bot；不把 MockBukkit/网页等同于游戏画面和真实插件交易 |

各层检查有交叉覆盖，62/19/13/4 不与 Java/前端测试数相加为“独立功能总数”。跳过不计通过。

## 七项覆盖与证据

### 1. 临时预览、应用与撤销

- 标题、正文、输入初始值、按钮 label/tooltip、尺寸和列数在合法草稿未失焦时即反映到原画布；不执行按钮中的命令或购买。
- 临时层不改正式文档、服务端基线、revision 或全局历史；空/非法数字保留输入和提示，不生成错误模型。连续输入失焦提交一次；Esc 取消。
- 颜色、渐变保留选区，窗口改色只做临时预览，应用一次撤销，取消恢复。覆盖多色、跨样式、部分替换、Unicode、空行和重读继续编辑；独立按钮图标未被文字格式修改。
- 保存晚返回不覆盖新草稿；切换、重读、全局撤销等清理临时层。约 3500 字符追加 10 次，含两个渲染帧耗时 23.8–34.0 ms；只代表该隔离实例，不是全部硬件性能保证。
- 证据：`livePreviewStore.test.tsx`、`editorStore.test.ts`、文本/颜色/渐变相关 Vitest；浏览器实时 11 项、颜色 23 项、渐变 19 项。已查看 `live-preview-browser.png`、`gradient-picker.png`。

### 2. 引用提示与草稿保护

- 后端沿用 `MenuValidator.referencePaths`；10 种已支持按钮位置/动作分支、多个引用者、自引用、误判及串行删除竞争均回归。普通字符串和玩家数据不当作菜单引用。
- 409 MENU_REFERENCED 弹窗按引用菜单分组去重，保留中文位置与原 JSON Pointer，提供“打开此菜单”。跳转前保护草稿；取消、读取失败、缺 details、旧请求晚返回均不静默覆盖当前内容，不自动重试删除。
- revision/YAML 冲突保持原语义，不误开引用弹窗，无强制或级联删除。
- 证据：`MenuDeletionReferencesTest` 16 项、`MenuReferencesDialog.test.ts` 和浏览器 9 项；已查看 `menu-references-browser.png`。浏览器冲突响应为模拟 REST，真实仓库规则另由 Java 测试验证，不宣称该弹窗由实际 Paper 现场触发。

### 3–4. 清理资格、恢复及交易竞争

- dry-run 显示候选、跳过原因和统计，不写玩家限购文件。NEVER、有效计数/未来冷却、pending、未决交易、恢复记录、无法识别/缺少可靠时间的数据保持；不用 mtime 判定玩家活跃。业务 JSON 默认保留。
- 归档与恢复均先做唯一且可核对的备份；失败不动源，执行前复查状态，恢复拒绝新购买或人工修改后的内容，不覆盖旧备份。
- 核对 `PlayerDataRetention` 的 `store.withPlayerMaintenance → limits.maintain`、`ProductionStore.begin` 门禁以及集合协调器。交易期间维护等待/拒绝，维护期间新交易拒绝；取消调用者不会提前释放真正任务，业务缓存/终态收据不改。
- 证据：`PlayerDataRetentionTest` 38 项、`LimitRetentionPolicyTest` 3 项及生产/限购测试。覆盖备份失败、备份后新恢复记录、UUID 队列、时钟回拨、源变化、缓存和 TIMED 重启恢复。
- 隔离 Paper 实际执行 dry-run、归档、精确恢复、变更冲突、重启未决记录保护；只使用脚本生成的测试数据，未使用原服务器玩家文件。未实际等待完整 24 小时任务周期。

### 5. 审计准确、脱敏与故障可观察

- 实际菜单 CRUD、冲突、YAML 导入、改密失败/成功、归档恢复和管理命令结果写入 UTF-8 JSON Lines；Web 为 local-admin-session，别名使用固定规范子命令，实际完成才记成功。
- 测试账号密码、哈希、Cookie、CSRF、测试正文和参数值均不出现在审计记录；没有读取服主真实凭据来测试。日志追加失败不更改已提交业务结果。
- `AuditLoggerTest` 11 项覆盖并发 800 条、真实 10 MiB 阈值、唯一轮转、轮转/追加故障、队列满、截断重启、锁、诊断失败和停服排空；`MenuAuditTest` 6 项及其他生产入口测试回归。
- 隔离 Paper 验证实际事件/结果、脱敏和多次停服后合法 JSON 行。轮转和磁盘失败通过故障注入/临时文件验证，不声称在实际 Paper 进程上人为耗尽磁盘。日志失败可见性通过固定报警码与计数验证，不承诺防篡改或硬断电零丢失。

### 6. 命令、权限、补全与快捷入口

- help/editor/main/open/reload、dialogcanvas 和原 data 行为保留；list 每页 10 个；edit 同 editor，仅本机地址，不绕过登录。dme 空闲注册，被占用跳过且保留原插件命令。
- 控制台需指定完整在线目标；离线/部分名字拒绝。权限沿用 dcmenu.admin/shortcut 和默认关闭的具体 command 节点；无权限菜单补全为空，不读磁盘。
- `ManagementCommandTest` 27 项、`MainMenuShortcutTest` 3 项和 `SavedMenuOpenerTest` 验证玩家/控制台、权限隔离、主线程、在线目标、实际 Future 完成、取消/离线/服务未就绪以及异步补全返回空。
- 隔离 Paper 实测分页、两个根别名、edit、控制台缺目标/离线拒绝、与冲突夹具插件共存和审计。实际在线玩家 Dialog、权限插件继承、Tab 展示及 Shift＋F 本轮尚无原版客户端验证。

### 7. 阶段 7 保护回归

| 保护 | 本轮依据 |
| --- | --- |
| 线程及关闭 | SchedulerTest、命令/显示/动作测试；`Stage7JointAcceptanceTest` 检查玩家/背包/权限/命令阶段处于主线程，文件收据不阻塞主线程；隔离正常 stop 排空并释放端口 |
| 持久化 | AtomicFiles、ProductionStorePersistence、MenuRepository、MenuSynchronization、LimitService 测试；原子移动不降级、写失败不发布缓存、补偿不覆盖外改内容；实际 JSON/YAML/基线及 pendingImport 重启保持 |
| 认证 | AuthServiceTest 21 项、AuthHttpTest 14 项；实际未登录/登录 Cookie、Origin/CSRF、改密/退出/重启失效、已有哈希不覆盖；开发代理 4 项 |
| 去重与版本 | ClickGuardTest 9 项、DialogCallbackAuditTest 17 项；联合点击测试在首单收据挂起时拒绝 20 次重复点击，实际完成前不释放保护；旧会话/角色/版本规则回归 |
| 占位符 | PlaceholderApiResolverTest 23 项、ConditionEngineTest 52 项；缺失、异常、null、未解析、非法数值、取反/!=、短路、WARN 脱敏与 ERROR 阻断回归 |
| 联合交易 | Stage7JointAcceptanceTest 9 项：异步点击→条件→限购→交易→必要持久状态→副作用→完成；placeholder/intent/playerdata/checkpoint/receipt/offline/stop/opFailure 八类边界及正常场景，含临时 OP finally 恢复与未知结果重启阻止 |

正常条件 FAIL 与 ERROR 继续区分；ERROR 不因 negate/OR 被转为成功，不执行成功业务链。普通显示文本仍使用自己的失败策略。未重新引入命令允许列表，player/op/console 身份语义保持。

## 未完成、限制及本轮异常

- 4 项既有 Java 跳过：DialogRendererTest 的 notice/confirmation/multi_action 三项缺 MockBukkit 原生 Dialog provider，ActionItemFactoryTest 的完整自定义剑默认属性一项受 MockBukkit 实现限制。未声称本轮通过这四项。
- 本轮无原版客户端、真实 Vault/PAPI 扩展/第三方命令交易验证；不将注入适配器当作真实提供者。没有强制终止/断电实验，不承诺跨 Vault、背包及多个文件的 ACID 或恰好一次执行。
- 浏览器使用真实 Edge/生产资源，但 API 为模拟夹具；Paper 使用真实 REST/文件和控制台，二者不是同一个浏览器连接实际 Paper 的端到端场景。原版字体、缩放、图标和渐变最终效果仍需游戏内检查。
- 初次实时浏览器启动超时、开发代理 esbuild spawn EPERM、Windows 查询隔离进程 TCP 端口拒绝访问；分别按权限流程重跑后通过，失败未当作通过，未放宽后端安全规则。
- Vite 既有单 JS 包约 1.114 MB（gzip 约 279 KB）体积提示、Java 过时 API/ByteBuddy Unsafe 提示仍存在，未因验收做无关拆包或依赖升级。
- 异步 Bukkit 补全仍安全返回空；别名只保证注册时冲突检查，不控制后加载插件强制覆盖。清理自动删除默认关闭，未知记录保守保留，备份和交易恢复记录不自动清理。

## 产物与证据

- 工作区导出：`dist/DialogCanvas-0.1.0.jar`，11,417,997 字节；未部署原服务器。
- SHA-256：`2180397ebdecf315a533dc635184ce189d8f51850ba05d8a67f451a6fa2d8126`。生产代码未改，可复现构建与阶段 8.5 摘要一致；两套本轮 Paper 夹具均使用该摘要，不借用历史 JAR 验证结论。
- 汇总：`build-reports/stage8-acceptance-results.json`，包含各层结果、当前测试套件计数、跳过明细、实例路径和摘要。JUnit：`plugin/build/test-results/test`；前端：`build-reports/stage8-frontend-results.json`。
- 浏览器：`live-preview-browser-results.json`、`mc-color-picker-results.json`、`gradient-picker-results.json`、`menu-references-browser-results.json` 及同目录截图。
- Paper：`stage8-paper-results.json` / `stage8-paper.log`、本轮重跑的 `stage7-paper-results.json` / `stage7-paper.log`。历史阶段报告不覆盖为本轮统计，使用本汇总判断本次产物。
- 其他：`dev-auth-guard-results.json`。收集脚本只核对已执行证据、资源和导出，**不能代替执行测试**；检查时间与同一 JAR 摘要，拒绝陈旧/失败结果。

## 复现

需要现有锁定的本地依赖、Java 25、独立 Edge 和 `build/stage4-paper` 固定版本测试缓存。以下命令只针对工作区，不用于原服务器：

```powershell
cd E:\project\menu\frontend
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=../build-reports/stage8-frontend-results.json
node node_modules/vite/bin/vite.js build
cd ..
$env:JAVA_HOME='E:\project\java25'
$env:GRADLE_USER_HOME='C:\Users\小米周\AppData\Local\Temp\DCMenu-verification\gradle-home'
./gradlew.bat build --rerun-tasks --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
node scripts/verify-live-preview.mjs
node scripts/verify-menu-references.mjs
node scripts/verify-gradient-picker.mjs
node scripts/verify-color-picker.mjs
node scripts/verify-dev-auth-guard.mjs
python scripts/verify-stage8-paper.py
python scripts/verify-stage7-paper.py
python scripts/verify-stage8-acceptance.py
```

Windows 受限环境中可能需要允许启动独立测试进程和查询其端口。不要用原服务器实例替代脚本夹具。

## 原版客户端人工复核清单（待进行）

1. 经部署授权后，正常停服再替换 JAR、启动。输入 `dcmenu editor`，在服务器电脑浏览器登录；修改文字/颜色/渐变/列数，分别应用、取消、撤销并保存重读。
2. 建立 A 打开 B 的引用，删除 B，确认弹窗显示 A 的正确分支与路径；A/B 含草稿时测试取消跳转仍保留内容。
3. 用实际客户端检查 `/dcmenu open <ID>`、控制台向在线玩家打开、按钮点击、主菜单 Shift＋F，分别用管理员/普通玩家测试 Tab 和权限；dme 被其他插件占用时使用 dcmenu。
4. 检查原生文本颜色/渐变、物品图标、布局和 tooltip；如需经济/PAPI 交易验收，使用另行授权的测试账户和测试服，不从本轮模拟结果推断真实扣款发货已验证。
5. 玩家数据维护仅先 dry-run 核查报告；本轮不要求在原服务器执行 archive 来证明验收。备份/恢复及故障注入已在临时数据验证。
