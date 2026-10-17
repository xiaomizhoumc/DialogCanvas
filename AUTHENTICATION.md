# DCMenu 第一版认证

> 首次安装、密码与当前网页登录步骤见 [Wiki 安装教程](docs/wiki/01-start/installation.md)。本文件是认证技术专题，按最新增补及 [WEB_API.md](WEB_API.md) 读取。

阶段 10.1 收尾：认证契约保持，当前回归覆盖过期、退出、改密和重启失效。前端收到 401 后保留当前页面内存中的菜单草稿与撤销历史，要求重新登录；特别是“保存已提交、列表刷新才失效”，会明确提示正式数据已保存，请勿盲目重复提交。重新登录不自动读取覆盖草稿。草稿不写 localStorage/sessionStorage，刷新或关闭页面后不保证恢复；主动退出仍需确认未保存修改。证据及人工范围见 STAGE10_SECURITY_CONFIG.md。

认证实现日期：2026-10-11。后续已接入菜单 CRUD、校验和元数据接口，见 WEB_API.md；本文件说明认证契约。

阶段 4 后续完整验收已在隔离 Paper 上验证登录/退出、Cookie、旧会话重启失效和新登录正常；Edge 无头仅验证根说明页。请求示例及人工范围见 STAGE4_ACCEPTANCE.md。下方认证步骤测试计数为实现时记录。

## 首次初始化与密码保存

- 配置路径：插件目录的 config.yml；password-hash 保存 BCrypt 哈希，不保存明文密码。
- password-hash 为空或旧配置缺少该键时，工作线程生成 24 随机字节对应的 32 字符 Base64URL 密码（192 位随机熵），使用 BCrypt cost 12 哈希。
- 读取原配置并保留配置键值，通过 AtomicFiles.write 原子替换 config.yml；首次重写不保留 YAML 注释与排版。
- 只有哈希持久化成功后才输出初始化密码，后续存在哈希的启动不再输出。
- 初始化密码直接写控制台，绕过 Bukkit Logger 和 System.out 日志桥接；正常认证不记录密码、哈希、Cookie、Session Token 或 CSRF Token。
- 外部启动脚本若重定向标准输出或录制终端，仍可能保存首次密码。这属于启动环境行为；插件没有额外创建明文密码文件。
- BCrypt 初始化和校验均在独立工作线程完成；onEnable 不等待 Future。认证初始化及监听完成后才标记 Web 服务就绪；失败清理 Web 资源，本机编辑器/API 不可用，插件保持启用并保留游戏测试命令。入口与端口诊断见 LOCAL_EDITOR.md。
- 忘记密码时：正常停服，把 config.yml 的 password-hash 清空，再启动并保存新输出的密码。禁止运行中手动修改认证配置。

阶段 7.3 补充：已有哈希在加载前检查 BCrypt 格式和 cost 4–16；生产新哈希仍为 cost 12。超过范围或格式损坏时拒绝启用认证，保留配置，不自动重置密码。配置对象文本隐藏哈希，配置 YAML 异常不附原文或原解析异常，防止启动堆栈泄漏。登录和改密还拒绝无效 Unicode 字符，避免 UTF-8 替换形成密码别名；合法中文/Emoji 的 72 字节边界通过。新密码最少 8 个 Unicode 码点，单个 Emoji 算一个；已有合法短密码仍可登录。完整审计与人工范围见 AUTH_SECURITY_AUDIT.md。

## 会话

- SecureRandom 生成 32 随机字节 Base64URL Session Token（43 字符，256 位随机熵）。
- ConcurrentHashMap 存储会话；不保存到文件。会话记录文本表示会隐藏 Token。
- 创建后固定 24 小时过期，不滑动续期；恰好到期时拒绝访问。
- 定期清理到期会话，访问时再次检查；最多保留 256 个有效会话。
- 退出立即撤销当前 Token；其他浏览器独立会话不受影响。
- HTTP 服务关闭时清空全部会话，取消待处理认证请求；新服务实例不会恢复旧 Token。
- 多标签页共享 Cookie；同一浏览器重新登录会覆盖该浏览器 Cookie，旧会话最多保留到失效时间。

Cookie 示例（Token 用占位符，不是真实凭据）：

```text
DCMENU_SESSION=<token>; Path=/api; Max-Age=86400; HttpOnly; SameSite=Strict
```

本地 HTTP 不设置 Secure，不设置 Domain，不使用要求 HTTPS 的 Cookie 前缀。浏览器通过 Cookie 自动携带 Token；Token 不返回到 JSON、URL 或 localStorage。JavaScript 能读取响应中的 CSRF Token，不能读取 HttpOnly Session Cookie。

## 路由与请求

| 路由 | 请求 | 成功响应 |
| --- | --- | --- |
| POST /api/auth/login | application/json：`{"password":"管理员密码"}`；必须有同源 Origin | 200，设置 Cookie，返回 authenticated/expiresAt/csrfToken |
| GET /api/auth/session | 会话 Cookie | 200，返回 authenticated/expiresAt/csrfToken |
| POST /api/auth/logout | 会话 Cookie、同源 Origin、X-CSRF-Token；无请求体 | 204，撤销会话并清除 Cookie |
| GET /health | 无认证 | 200，文本 ok |

JSON 响应示例：

```json
{
  "authenticated": true,
  "expiresAt": "2026-10-12T00:00:00Z",
  "csrfToken": "<csrf-token>"
}
```

- 除字面路径 POST /api/auth/login 外，所有 /api 和 /api/* 都在全局 before 中认证，包括未知端点、不支持的方法和编码后的 API 路径。
- 未登录访问 /api/menus、/api/meta 返回 401；后续菜单 API 已实现，登录后可访问，未知端点仍返回 404。
- GET/HEAD/OPTIONS 不修改状态；其他方法校验同源 Origin，已认证的写请求还校验 X-CSRF-Token。
- 来源限定 http://127.0.0.1:<配置端口>；默认端口 80 使用浏览器标准省略端口的来源。Host 也限定相应回环地址。
- 不启用跨来源 CORS；本轮没有前端页面或跨端口 Vite 访问例外。
- 后续本机 Vite 开发服务仅接受其实际 127.0.0.1 端口的同源写入，转发前检查原始 Host/Origin，并关闭开发 CORS；随后改写为后端同源，后端继续校验 Cookie 与 CSRF，没有任意来源例外。
- 所有 API 响应 Cache-Control: no-store。
- 登录体只允许一个字符串 password 字段，拒绝未知字段、重复字段、尾随 JSON 和错误媒体类型；请求体上限 1 MiB。
- 空密码或 UTF-8 超过 BCrypt 72 字节限制的密码与普通错误密码统一返回 INVALID_CREDENTIALS，不截断密码。

## 失败限制与错误

- 本机管理员共享一个失败计数窗口：一分钟最多 5 次失败。正在进行的校验也计入占用，防止并发绕过。
- 成功登录重置失败计数；窗口到期后可再次尝试。触限返回 429 和 Retry-After: 60。
- BCrypt 工作池固定 2 个线程，队列上限 32；服务关闭或队列满返回 503，不在请求线程或主线程执行哈希。

统一错误：

```json
{"error":"密码错误","code":"INVALID_CREDENTIALS"}
```

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 登录 JSON 格式错误 |
| 401 | INVALID_CREDENTIALS | 密码错误（统一响应） |
| 401 | AUTH_REQUIRED | 会话缺失、错误、过期或已撤销 |
| 403 | ORIGIN_INVALID | Host/Origin 不受支持或写请求缺少 Origin |
| 403 | CSRF_INVALID | 已登录写请求缺少或不匹配 CSRF Token |
| 404 | API_NOT_FOUND | 已认证，但接口尚未实现或不存在 |
| 405 | METHOD_NOT_ALLOWED | HTTP 方法不支持 |
| 413 | HTTP_413 | 请求体超过上限 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 登录请求不是 application/json |
| 429 | LOGIN_RATE_LIMITED | 登录失败限制 |
| 503 | AUTH_UNAVAILABLE | 认证未就绪、关闭、队列满或校验内部失败 |
| 500 | INTERNAL_ERROR | 未预期的请求处理错误，不暴露异常内容 |

## 验证

AuthServiceTest 使用可注入 Clock 验证 24 小时边界、失败窗口和生命周期；首次初始化测试在 MockBukkit 主线程发起，验证密码处理及输出回调位于工作线程。

AuthHttpTest 启动真实 Javalin，仅监听临时的 127.0.0.1 端口，通过 Java HttpClient 验证登录、Cookie 自动携带、错误密码、未登录未知路由、同源/CSRF、过期、退出、服务重启、请求格式与大小限制。

认证步骤未启动真实 Paper 服务端或浏览器；HTTP Cookie 测试不等同于浏览器页面验收。菜单接口在后续步骤已实现，具体范围与最新验证结果见 WEB_API.md。

完整 Gradle build 通过：198 项测试，195 项通过，3 项为既有 MockBukkit 原生 Dialog provider 缺失跳过，零失败。新增认证测试 16 项全部通过（7 项服务测试、9 项实际 HTTP 测试）。

## 官方参考

- [Javalin HTTP handlers 与异步请求](https://javalin.io/documentation)：本项目保留 7.2.2，签名额外通过本地依赖 javap 和编译核对。
- [BCrypt Java 实现](https://github.com/patrickfav/bcrypt)：使用现有 0.10.2 依赖的 hashToString/verify。
- [OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)。
## 修改管理员密码（2026-10-14）

登录网页后，点击顶部“修改密码”。填写当前密码、新密码和新密码确认，点击“确认修改”。新密码至少 8 个字符，UTF-8 编码最多 72 字节；空格是密码的一部分，不会自动裁剪。BCrypt 不截断超长密码。忘记当前密码的重置不属于此入口。

修改成功后立即清除所有内存会话和当前 Cookie，所有浏览器需用新密码重新登录，无需重启服务器。本页菜单文档、未保存状态和撤销历史保持；登录成功后可继续保存，关闭页面仍会丢失尚未保存的草稿。其他标签页在下次请求时收到 401。

接口：`POST /api/auth/password`，JSON 包含 `currentPassword`、`newPassword`、`confirmPassword` 三个字符串。需要有效 Cookie、同源 Origin 和 X-CSRF-Token。204 表示哈希已原子持久化并失效全部会话；不返回密码、哈希或新 Token。旧密码错误为 403 CURRENT_PASSWORD_INCORRECT（不退出现有会话）；422 INVALID_NEW_PASSWORD / PASSWORD_MISMATCH；401 AUTH_REQUIRED；429 LOGIN_RATE_LIMITED；409 PASSWORD_CONFIG_CHANGED；503 PASSWORD_SAVE_FAILED / AUTH_UNAVAILABLE。

新旧密码校验与 BCrypt 计算、config.yml 读写只在有界认证工作线程执行；当前密码失败复用现有一分钟五次的尝试限制。重新检查会话和哈希版本后原子写 config.yml，再发布新哈希并清空会话。并发修改只有一个成功，旧密码的在途登录不能在修改后创建有效旧会话。保存失败保留旧哈希及会话；配置哈希在外部被修改时拒绝覆盖。其他配置项按读取时的值保留；YAML 重新排版，不保留手写注释。密码明文不进入日志、本地存储、菜单或 URL。

网络中断/响应不确定时不要重复提交修改；关闭窗口后先尝试新密码登录，再尝试旧密码，核查实际结果。插件重启以 config.yml 中的新哈希初始化，不再打印初始密码。验证报告见 build-reports/PASSWORD_CHANGE.md。

阶段 7.2 补充：配置读写拒绝符号链接、junction 和非普通文件；保存完整临时配置后、原子替换前复核刚读取的旧配置字节，期间其他配置改动也不覆盖。首次默认配置只在仍不存在时创建。修改密码提交失败仍保留旧内存哈希/会话；文件原子替换不承诺与外部编辑器的原子比较并交换，详情见 PERSISTENCE_AUDIT.md。

