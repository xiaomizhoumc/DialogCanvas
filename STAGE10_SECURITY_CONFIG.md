# 阶段 10.1：安全与配置收尾

日期：2026-10-16。结论：确认的两类问题已修复，本轮自动测试、隔离浏览器及隔离 Paper 回归通过；原版客户端与真实权限/经济插件验收仍需人工完成。

## 范围和依据

先核对 PROJECT_CONTEXT.md 最新 8.6 记录、STAGE7_AUDIT.md、STAGE8_ACCEPTANCE.md，再检查当前实现。本次沿用既有认证、文件协调器、AtomicFiles、菜单模型与校验器，没有重建认证/存储系统，没有新增 API 或命令，没有部署原服务器或修改其数据。

证据汇总：`build-reports/security-closeout-results.json`。收集脚本还将最终 JAR 中的 AuthService、AuthMiddleware、EmbeddedHttpServer、MenuRoutes、MenuRepository、MenuValidator、FileStorage、AtomicFiles、PluginConfig、ManagementCommand 十个主类与 8.6 隔离实例中的历史 JAR 逐字节比较，确认这些审计入口未改；结合当前完整回归复用旧审计，不能将主类比较当作全部嵌套类或运行环境的完整性证明。

## 确认并修复的问题

| 问题与影响 | 证据 | 最小修复与结果 |
| --- | --- | --- |
| P2：正式保存完成后，刷新菜单列表返回 401，仅显示列表失败，未切回登录状态；用户无法明确判断是否需要重新登录 | 新增浏览器夹具先保存成功，再令列表请求 401；修复前登录入口等待超时，写请求已发生一次。`security-closeout-before-fix.json` | `frontend/src/App.tsx:180` 调用现有 showError 处理 401，同时明确提示“正式数据已保存，会话失效，草稿保留，请勿重复提交”。保留文档、保存基线和撤销历史，不重复写入。浏览器 7 项通过 |
| P1：动作失败诊断记录第三方异常原文/堆栈，可能把命令参数或持久化异常中的业务内容带进服务器日志 | 三条故障注入测试均在修复前复现脱敏断言失败；使用人工构造的测试标记，不是已确认的真实凭据泄漏。摘要 `security-diagnostics-before-fix.json` | `TransactionalActionEngine.java:59`、`:329`，`ProductionActionEngine.java:75`，`DialogRenderer.java:361`、`:364` 改记固定诊断码，事务路径保留事务 UUID、状态及失败码，不打印异常原文或附带 Throwable。3 条新测试通过 |

内部 ActionChainResult、原始失败传播、交易恢复记录及回滚逻辑保持。固定诊断不表示具体命令已执行成功；操作人员应按事务状态核查，不能自动重试。第三方插件自己记录的日志不受本插件过滤控制，私有交易恢复文件仍可包含恢复业务所需信息。

另检查了“401 后晚到的已授权读取覆盖草稿”假设，当前文档/请求保护已阻止，浏览器复验通过；没有据此制造生产修改。

## 安全与配置检查表

下表的测试数为当前完整构建中相应套件数量，互有交叉，不应累加为独立测试总数。

| 检查项 | 当前入口及依据 | 本轮证据/结果 |
| --- | --- | --- |
| 所有 API 认证 | `AuthMiddleware.java:23` 全局 before；仅精确 `POST /api/auth/login` 豁免 Session。未知方法、未知 API、编码规范化路径仍认证 | AuthHttpTest 14：全部注册端点、方法、编码路径、Cookie、请求大小与错误脱敏通过；未新增漏挂中间件的 API |
| 新增改密入口 | `EmbeddedHttpServer.java:139` 使用同一中间件与 AuthService；当前密码、新密码、确认值严格校验 | AuthHttpTest 改密的认证/Origin/CSRF、错误密码限流、全部 Session 失效通过；AuthServiceTest 并发改密、旧登录在途请求不能复活会话通过 |
| Origin/CSRF/Host | `AuthMiddleware.java:23` 校验本机 authority；写操作包含 login 的 Origin 校验；非 login 写请求还校验内存 Session 的 CSRF。不开放任意来源 CORS | 实际 Paper 外来/缺失 Origin、错误 CSRF 拒绝；既有开发代理规则保持。静态页面与 /health 公开属既有设计，不提供菜单数据 |
| 会话生命周期 | `AuthService.java:144` 固定 24 小时；logout 当前会话失效，成功改密全部失效，close/restart 清空，不写磁盘 | AuthServiceTest 21 + AuthHttpTest 14 全通过；实际 Paper 登录/退出/重启失效通过。24 小时边界使用受控 Clock，并未实等一天 |
| 失效后的前端 | App 的 showError/登录及保存后列表刷新；Cookie 自动携带，CSRF 在内存，无 Token URL/持久化存储 | 独立 Edge 当前生产 dist + 模拟 REST：失效/晚响应/草稿/重登/撤销 7 项，改密窗口/重登保留草稿另 7 项通过。主动退出仍按既有未保存确认规则执行 |
| 密码初始化/限流 | SecureRandom 初始密码与 Session；BCrypt cost 12；严格 Unicode/UTF-8 最多 72 字节，后台工作线程；先原子持久化哈希才输出一次密码 | AuthServiceTest 覆盖初始化失败不就绪/不输出、已有哈希不覆盖、字节边界、并发与限流。PluginConfigTest 5 覆盖哈希格式/cost及配置脱敏；未读取或更改服主真实密码 |
| 本机监听/配置 | `EmbeddedHttpServer.java:154` 固定 127.0.0.1，端口读配置且限制 1–65535；RUNNING 才发布地址 | 最新 JAR 实际 Paper socket 仅回环、控制台地址、两次停止端口释放通过；EditorHostingTest 6 包含真实端口占用、初始化失败无地址及资源清理。没有将本轮占用单测称为隔离 Paper 的占用测试 |
| 命令权限/参数 | `ManagementCommand.java:70`、`:106`、`:118`；默认管理权限 dcmenu.admin，细分权限默认 false，main 保留 dcmenu.shortcut；异步补全返回空；data 仅控制台且参数为 UUID | ManagementCommandTest 27 覆盖委派/无泄露/在线完整目标/别名/就绪/异步/实际结果审计；命令不会绕过网页登录或接受任意文件路径 |
| player/op/console | `TransactionalActionEngine.java:416` 起校验命令结构、长度与变量，再按所选身份分发；临时 OP 在 finally 恢复 | TransactionalActionEngineTest 68 + ProductionActionEngineTest 35 + Stage7JointAcceptanceTest 9 全通过；新增故障注入还检查抛异常后恢复 OP。没有恢复命令允许列表，权限判断仍由执行身份和目标命令处理 |
| 真实模型和引用 | `MenuRepository.java:188`、`:200`、`:261`、`:303`；使用统一 MenuDefinition/严格 Jackson、MenuValidator.prepare/referencePaths，集合协调器内提交。YAML 手动入口复用同一规则 | MenuHttpTest 10、MenuRepositoryTest 11、MenuSynchronizationTest 13、引用相关套件均回归；非法模型与缺失引用不覆盖，删除引用保护保持 |
| JSON/YAML/revision | 正式 JSON → 发布注册表 → 导出 YAML/基线；失败补偿沿用。revision 不透明；未导入 YAML/未知基线拒绝自动覆盖；导出失败明确正式数据已保存 | 实际 Paper 创建/校验/并发 revision 409/YAML pendingImport 保存及删除拒绝/手动导入/重启保留均通过；导出和发布失败由故障注入回归。没有声称多文件天然原子 |
| 路径/请求大小 | FileStorage + DocumentIds + AtomicFiles：固定类型目录、受限 ID、Windows 保留名、真实父目录/符号链接检查；HTTP 1 MiB，菜单文件上限 1 MiB，JSON 深度 64，拒绝重复键/尾随值 | AtomicFilesTest 5、配置/存储/菜单套件及 AuthHttpTest 超限请求通过；不将文件 mtime 用作保留依据；配置及备份不在 web 静态目录中 |
| 错误与日志 | HTTP 统一固定 `{ error, code, details? }`，不返回堆栈；AuditLogger 固定字段白名单。新增动作诊断修复见上 | AuditLoggerTest 11、MenuHttpTest 错误与敏感信息验证、3 条新增故障注入通过；请求正文、密码/哈希、Cookie、CSRF、Token、命令参数及占位符结果不写入这些诊断 |

当前 API 共 **11 个方法/路由组合**：login、logout、session、password 四项；menus 列表/读取/创建/更新/删除/validate 六项；meta 一项。未增加新接口。

## 本轮验收结果

| 层级 | 结果 | 限制 |
| --- | --- | --- |
| TypeScript / Vite | 均成功 | 既有大包体积提示仍存在，不属于安全绕过 |
| 前端单元测试 | 156 通过，0 失败/跳过 | 报告 stage10-1-frontend-results.json |
| Java 完整 Gradle build | 596 项：592 通过、4 项既有跳过、0 失败/错误 | 增加 3 条日志故障注入；4 项 MockBukkit 能力跳过不计通过，完整套件在本轮实际执行 |
| 浏览器 | 14 项通过，无脚本异常，截图已查看 | 独立 Edge + 当前生产 dist + 模拟 REST，不冒充真实 Paper 浏览器联调 |
| 隔离 Paper | 最新同一 JAR，Build 74，13 项通过，两次启动/停止 | 实际 HTTP、文件与注册表、控制台、重启/端口；无 Minecraft 客户端、真实 Vault/PAPI 购买 |
| 资源/导出 | 1050 个前端资源与 JAR 逐字节匹配；默认资源密码哈希为空 | 仅导出工作区 dist，不部署原服务器 |

JAR：`dist/DialogCanvas-0.1.0.jar`，11,418,092 字节。

SHA-256：`052bf87b12a81bd2dd831754830a780845fbd0f659d53c59112ccab28f750f45`。

### 复现

在 frontend 执行（使用项目既有依赖）：

```powershell
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=../build-reports/stage10-1-frontend-results.json
node node_modules/vite/bin/vite.js build
```

在项目根目录执行；运行浏览器前已构建 production dist：

```powershell
node scripts/verify-security-closeout.mjs
node scripts/verify-password-dialog.mjs
$env:JAVA_HOME='E:\project\java25'
$env:GRADLE_USER_HOME='C:\Users\小米周\AppData\Local\Temp\DCMenu-verification\gradle-home'
./gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
python scripts/verify-stage7-paper.py --stage10-1
python scripts/verify-security-closeout.py
```

隔离脚本只使用工作区夹具，不操作原服务器；收集脚本核对全套报告、最终 JAR 摘要及资源后复制到 dist。阶段 7 原脚本默认行为未改，新增参数单独输出 stage10-1 报告，保留旧阶段验收证据。

## 待人工验证与实际保证边界

1. 经服主授权另行部署后，用原版客户端验证真实权限插件的 player/op/console 分发、主菜单快捷键和在线展示；本轮不宣称已在原服务器/客户端复测。
2. 真实 Vault/PAPI 及其他插件内部抛错/日志行为不由 mock 或本插件日志过滤完全保证。确认异常时先按事务状态核查，不自动重复扣款发货。
3. 草稿仅保留在当前页面内存：失效后重新登录可继续编辑；刷新、关闭或崩溃不会将草稿持久保存。关闭提示不能保证浏览器一定阻止离开。
4. 未做硬断电实验，不承诺断电零丢失、审计防篡改、不合作外部编辑器的跨进程事务。保留阶段 7/8 已说明的恢复边界。
5. 未发现本轮确认的未修复 P0；不能据此宣称所有潜在问题均已排除。没有对真实密码、哈希或会话作人工处置，原服务器 JAR、配置和玩家数据未被访问/更改。
