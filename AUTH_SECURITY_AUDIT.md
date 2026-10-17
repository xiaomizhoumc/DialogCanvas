# 阶段 7.3：认证与敏感信息审计

日期：2026-10-15。复用 AuthService / AuthMiddleware；未修改服主密码、原服务器文件或部署 JAR。报告和扫描器只输出位置、问题类型、计数和验证结果，不输出凭据值或凭据摘要。

## 确认的问题与修复

| 位置 | 问题类型 | 修复与验证 |
| --- | --- | --- |
| `plugin/src/main/java/dialogcanvas/config/PluginConfig.java:93` | record 默认 `toString()` 包含密码哈希，存在未来日志泄漏入口 | 改为隐藏哈希；测试检查对象文本不含测试哈希。没有证据表明此入口已经泄露服主哈希。 |
| `PluginConfig.java:82`；`AuthService.java:84` | SnakeYAML 语法/重复键异常包含配置原文，可能随启动异常堆栈输出 | 共享配置解析入口只给出固定诊断，不携带原解析异常；测试检查完整异常链和堆栈不含测试敏感标记。修改密码原有异常处理同样不返回原文。 |
| `PluginConfig.java:67`；`AuthService.java:74` | 已配置哈希的直接服务入口未校验格式；配置允许极高 cost，可能长时间占用认证工作线程 | 启动前检查格式、版本和 cost 4–16，调用固定 BCrypt 0.10.2 的解析器核对；生产新哈希仍为 cost 12。非法哈希不启用认证、不覆盖配置、不输出初始密码。 |
| `AuthService.java:203`；`frontend/src/components/ChangePasswordDialog.tsx:6` | 孤立 UTF-16 代理项转换 UTF-8 时被替换，使不同密码输入产生相同 BCrypt 校验结果；旧最短长度按 UTF-16 单元计数，少于 8 个 Emoji 也能通过 | 测试在固定 BCrypt 库中复现编码别名；登录/旧密码验证/新密码保存拒绝无效 Unicode，前端同步校验。新密码最少 8 个 Unicode 码点，Emoji 也按一个计数；不改变已有合法密码的登录。合法中文与 Emoji 的 72 字节边界通过；超长输入不截断。 |
| `frontend/vite.config.ts`；`frontend/dev/localApiGuard.ts` | 开发代理无条件改写 Origin，未检查浏览器原始来源 | 转发前检查本机 Host 与同源 Origin，关闭开发 CORS；真正启动临时 Vite 与空白 HTTP 后端验证：外部/缺失写入 Origin 和外部读取来源均拒绝且未转发，同源请求仍可转发。未改变生产中间件或取消 CSRF。 |
| `AuthService.java:250` | 取消登录 Future 后，后台校验仍可能创建无法交付的会话，占用有效会话名额 | 已取消的排队任务不运行；在途校验的结果交付失败时移除刚生成的对应会话。测试用受控时钟在校验中取消并等待 worker 结束，确认无残留且后续登录正常。 |

## 认证链路审计表

| 入口/位置 | 依据与结果 |
| --- | --- |
| `AuthService.java:35,68–104` 首次初始化 | SecureRandom 24 字节生成初始密码；工作线程 BCrypt cost 12；完整配置条件原子替换成功后才发布哈希并输出。失败不打印、不启用认证。已有哈希只校验并加载，重启不重写配置；新增字节一致性验证。 |
| `InitialPasswordConsole.java:13` 首次输出 | 唯一明确例外，直接终端或进程 stdout descriptor，绕过 Bukkit/System.out 日志桥。外部 stdout 重定向/终端录制仍可能保存初始密码，见人工项。 |
| `AuthService.java:112,156,203` 密码长度 | 登录/旧密码验证均要求合法 Unicode 且 UTF-8 ≤72 字节；新密码最少 8 个 Unicode 码点，前后端一致。空格不裁剪、密码不截断、不做 Unicode 归一化；组合字符可能包含多个码点，不按视觉字形分组。已有合法短密码仍可登录，最短长度只约束新密码。 |
| `AuthService.java:33,51,114,214` 哈希与限流 | 生产 cost 12；兼容加载 4–16。2 个独立工作线程、队列 32；拒绝时 503。单管理员全局一分钟 5 次失败，计入在途校验；成功重置、分钟边界恢复。修改当前密码失败复用限制；有 HTTP 测试。 |
| `AuthService.java:36,61,128,147,233` 会话 | SecureRandom 32 字节 Token 与独立 CSRF Token；ConcurrentHashMap，最多 256 会话。创建起 24 小时固定到期，恰好到期拒绝；每分钟及访问/新登录清理。容量和到期回收已验证，文本表示隐藏两种 Token。 |
| `AuthService.java:153,156,268` 撤销 | logout 仅撤销当前会话；修改密码先持久化再清空全部会话；关闭清空会话并结束待处理 Future，重启不恢复。并发密码修改和在途旧密码登录有哈希版本检查；修改失败保留旧密码及会话。 |
| `AuthMiddleware.java:23–44,51` 路由 | 全局 before 覆盖 `/api` 和 API 子路径、未知接口与错误方法；只有字面路径 POST login 免会话认证，仍检查 Host/Origin。所有已注册 API、编码/重复斜线/规范化路径及非 POST login 有实际 HTTP 验证，未发现生产认证绕过。 |
| `AuthMiddleware.java:38–44` 写入 | GET/HEAD/OPTIONS 不写状态；其他方法要求精确 Origin 和常量时间比较 CSRF。读取若带 Origin 也要求匹配；无任意来源 CORS。开发代理另外校验原始来源。 |
| `EmbeddedHttpServer.java:123–143,173–212` Cookie/响应 | HttpOnly、SameSite=Strict、Path=/api、24 小时；本机 HTTP 不用 Secure，不设 Domain。退出/改密清 Cookie。Token 仅放 Cookie，不在 URL 或 JSON；认证后的 JSON 返回 CSRF 是设计要求，Cache-Control no-store。CookieManager 已实际验证本机 HTTP 携带。 |
| `EmbeddedHttpServer.java:104–115,153–165,196–202` 错误 | JSON/重复字段/媒体类型/请求大小限制有效；错误固定 `{error,code}`，不附原始异常、密码、哈希或 Token。损坏登录及改密请求有敏感标记回归测试；没有请求体/认证请求日志。 |
| `EmbeddedHttpServer.java:76–95` 初始化失败 | 初始化失败不绑定端口、不发布地址，清理 Web 资源；新增持久化失败验证。主插件按原启动故障路径报告，不宣称 Web 可用。 |
| `frontend/src/api/client.ts:29–108` 客户端 | Cookie credentials；CSRF 模块变量，仅内存；退出/改密后清除，认证代次防止旧响应恢复 CSRF。密码只在 POST JSON，不拼 URL。新增测试检查不调用本地/会话存储。 |
| `frontend/src/App.tsx:49,68,138`；`ChangePasswordDialog.tsx` | 登录密码为 React 局部状态，成功后清空；错误时保留在本页用于重输。改密字段成功/失败均清空，关闭组件后不保留。不进入菜单/Zustand 文档/撤销历史。浏览器密码管理器是否保存由用户决定。 |
| `frontend/src/components/mcColors.ts:57–80` | 唯一生产 localStorage 使用点仅保存规范化颜色列表；无认证、菜单或 Token 持久化。 |

> 撤销会话阻止之后的认证请求；撤销前已通过认证的普通业务请求可能完成，不代表 Token 仍有效。已开始的 BCrypt 运算不保证立即中断，关闭后不能提交新哈希或创建有效会话；运算一直在工作线程。

## 文件与构建产物扫描

- 清单：`build-reports/auth-sensitive-inventory.json`；复现：`python scripts/audit-sensitive-artifacts.py`。只读原服务器插件配置进行当前哈希对照，并检查 43 份当前/历史日志（含 gzip），没有匹配到报告规则涵盖的凭据输出；未修改这些文件。
- 对源码、前端、示例、配置、日志、构建报告和测试 XML/HTML 做脱敏扫描，排除 node_modules/Gradle 缓存；只报告位置和类型。额外检查成品 JAR 的默认配置和文本资源，没有打包实际凭据。精确数量及 JAR 检查见 `build-reports/auth-audit-results.json`。
- `build/stage4-paper/plugins/DialogCanvas/config.yml:3`、`build/stage5-paper/plugins/DialogCanvas/config.yml:3`、四个 `build/text-workflow-paper-*/plugins/DialogCanvas/config.yml:3`、三个 `build/thread-audit-paper-*/plugins/DialogCanvas/config.yml:3`：9 份隔离测试 BCrypt 哈希。使用固定库验证其对应明确测试凭据，报告不输出值；未与当前服主哈希匹配。详见 `auth-fixture-provenance.json`。不改写或清理这些配置。
- `AUTHENTICATION.md` 登录示例：标明占位符；`scripts/verify-stage4.py` 错误密码请求：明确测试凭据。浏览器附带扩展/Wallet 静态源码中的 password 字段为供应商代码/本地化，不是 DCMenu 认证数据。没有把这些匹配直接称为泄漏。
- 未发现当前服主哈希复制、字面有效 Token、初始密码输出或凭据 URL 出现在已扫描文本中；这是有限范围扫描结果，不是对全部历史或外部文件的保证。
- 浏览器配置目录有 Cookie/Login Data 数据库；HttpOnly Cookie 在浏览器自身 Cookie 存储属于正常设计。未解密二进制或 OS 加密数据，不能宣称旧浏览器目录完全无敏感信息；开发测试目录不可直接作为发布内容共享。

## 验证与边界

- Java 全量 build：413 项测试，410 通过、3 项既有 Dialog provider 跳过、0 失败。本轮增加 12 项，涵盖泄漏堆栈/文本表示、初始化失败、哈希参数、UTF-8、会话容量/取消清理、全部 API 与规范化路径；保留并发改密、限流、过期、退出、重启及 Cookie 测试。
- 前端 TypeScript 与 16 项针对性 Vitest 通过；真实 Vite 请求 4 项验证通过，最终产物结果见 `auth-audit-results.json`。没有用服主密码登录原服务器，未更改原服务器状态。
- 原版客户端和原服务器真实密码修改未测试；没有替用户执行改密、重置或清理浏览器数据。

## 人工处置

1. 如果曾把首次启动终端输出重定向到文件，或共享过终端录像/含配置哈希的旧错误堆栈，检查那些位置；确认泄漏后由服主通过现有改密入口修改密码。此次未发现已扫描文本内的真实凭据泄漏，不要求无依据地更改密码。
2. 发布只分享成品 JAR 与经过检查的示例；不分享服务器 config.yml、build 下隔离服务数据、完整浏览器配置目录或未经筛查的日志。
3. 已配置 cost 超过 16 的哈希现在会明确拒绝启动认证，磁盘内容不变；需要服主自行使用可信工具生成可支持的哈希。旧低 cost 哈希仍兼容，后续通过正常改密升级至生产 cost 12；本次不自动更改。
4. 已知存在无效 Unicode 密码的配置应由服主主动核查并更换为正常文本；不能依赖此前编码替换的别名。浏览器密码管理器、终端录制和系统备份不属于插件清理范围。
