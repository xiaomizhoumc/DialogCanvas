# 阶段 4 Web API 验收

日期：2026-10-11。先核对 `WEB_API.md`、`AUTHENTICATION.md`、`LOCAL_EDITOR.md`、`PROJECT_CONTEXT.md`，以及命令入口、HTTP/认证、菜单路由、Repository/Registry、模型校验与文件存储实现。

## 结论与范围

阶段 4 后端契约在自动测试和隔离的真实 Paper 实例上通过验收。没有发现需修改生产实现的阻断问题；新增三项端到端测试与可复现验收脚本。
未扩展模板、历史、diff、WebSocket 或前端编辑器。

- **自动测试**：完整 Gradle build 成功，250 项测试，247 项通过、3 项既有 MockBukkit 原生 Dialog provider 限制跳过，零失败。Web 包内 65 项全部通过。
- **实际服务器**：Paper `26.1.2-74` / API `26.1.2.build.74-stable`，Java 25；项目内 `build/stage4-paper` 隔离实例，Web 端口 18080、游戏端口 25575，均使用本机地址。已正常停止。
- **实际浏览器自动验证**：Edge `155.0.4283.45` 无头实例，使用独立配置目录；加载实际 Paper 根页面，DOM 和截图确认中文说明显示正常。这不是日常浏览器窗口的人工操作验收。
- **原服务器**：没有更新或启动 `E:/project/server26.1.2`，没有读取其密码或使用其会话；验收使用独立测试配置、哈希和菜单数据。

验收与最终构建 JAR 的 SHA-256 相同：

```text
978AF4029E6E0F21ACA657BC3994445995279DF2628A12BC350BB39A6EE22A6D
```

## 验收矩阵

| 项目 | 自动测试结果 | 实际 Paper / 浏览器结果 |
| --- | --- | --- |
| 控制台地址 | MockBukkit 控制台输出实际配置端口与尾部 `/` | `dcmenu editor` 输出 `http://127.0.0.1:18080/`；旧测试子命令仍识别 |
| 本机根页面 | HTTP 200，说明页、UTF-8、缺失资源 404 | Paper 返回说明页；Edge 无头 DOM/截图显示“编辑器尚未安装完成” |
| 登录/会话/退出 | 成功、错误密码、Cookie 自动携带、过期边界、退出、重启均通过 | 错误密码 401；登录/会话 200；退出 204 后会话 401 |
| 未登录菜单/meta | 未知/编码 API 路径、错误方法也认证 | menus/meta/session 均为 401 AUTH_REQUIRED |
| 新建/读取/列表/修改/删除 | CRUD 及具体错误码通过 | 新建 201，读取/列表/修改 200，删除 204 后读取 404 |
| 未保存校验 | 正确/错误/引用/模型/业务字段均覆盖 | 有效 200 且不落盘；非法更新 422 VALIDATION_FAILED |
| 非法配置保护 | 新增测试验证缺按钮、非法 ID、缺失引用均不改原始字节/注册表 | 422 后 YAML 原始字节、revision、GET 快照完全不变 |
| 文件/内存一致性 | YAML 解析值、文件摘要、注册表 JSON、GET 响应一致；故障补偿覆盖 | 创建、修改、并发赢家及重启后，文件 SHA-256 与 GET 的 registry revision 相同，完整 GET 响应匹配保存结果 |
| 并发冲突 | 新增真实 HTTP 同时 PUT，两个不同标题，同一 revision，仅一个提交 | 200 + 409 REVISION_CONFLICT；赢家标题持久化、发布，输家不覆盖 |
| 重启保留菜单、撤销会话 | 新增服务重建联合测试：磁盘菜单恢复、旧 Cookie 拒绝、新登录正常 | 正常停服并重新启动实际 Paper；完整菜单/revision 保留，旧会话 401，新登录成功 |
| 仅本机监听 | 固定绑定代码及配置限制、回环 HTTP 验证 | Windows TCP 监听表按 Paper PID 查询，仅 `127.0.0.1:18080` |
| 关闭释放端口 | 会话失效、Repository 关闭、端口重新绑定、重复关闭通过 | 两次 Paper 正常 stop 后，Python socket 成功重新绑定 18080 |
| 端口占用/服务降级 | 真实 ServerSocket 占用验证 FAILED、无地址、资源清理 | 本轮不额外复测真实 Paper 端口占用；自动测试通过 |
| 原子写入/发布失败 | 文件写入失败、发布后抛错、恢复失败、删除补偿均通过 | 本轮不在真实服务器人为破坏文件系统；故障注入证据来自自动测试 |

自动测试的服务重建不等于插件重启；本轮另外执行了真实 Paper 进程停止和重新启动。
文件摘要验证不额外承诺突然断电的目录 fsync 或崩溃事务日志保证。

## 证据

- `build-reports/stage4-http-results.json`：15 项实际实例检查均 PASS，浏览器验证标记为 true。不保存密码、Cookie、Session Token 或 CSRF Token。
- `build-reports/stage4-paper.log`：实际版本、控制台入口和两次启停；隔离实例预置测试 BCrypt 哈希，没有首次密码输出。
- `build-reports/stage4-root.png`：实际 Edge 根页面截图，已目视检查。
- `plugin/build/reports/tests/test/index.html`：完整自动测试报告。
- `plugin/src/test/java/dialogcanvas/web/menu/Stage4AcceptanceTest.java`：新增三项联合测试；复用实际 HTTP 和生产 FileStorage。

Paper 的离线测试配置、Java Unsafe、管理员运行及版本更新提示属于隔离实例/运行环境日志，本轮没有更改既定 Paper 版本。插件启停、认证和 CRUD 没有异常。

## 可复现请求：PowerShell 7

先在目标服务器控制台执行 `dcmenu editor`，把实际端口填入下面的 `$dcOrigin`。以下例子要求 PowerShell 7 的 `-SkipHttpErrorCheck`；密码通过隐藏输入提供，Cookie 保存在 WebRequestSession 内存中。

```powershell
$dcOrigin = 'http://127.0.0.1:8080'
$dcSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$dcHeaders = @{ Origin = $dcOrigin }

function Invoke-DcApi($Method, $Path, $Body = $null) {
    $parameters = @{
        Uri = $dcOrigin + $Path
        Method = $Method
        WebSession = $dcSession
        Headers = $dcHeaders
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes(
            ($Body | ConvertTo-Json -Depth 50 -Compress))
    }
    $response = Invoke-WebRequest @parameters
    $payload = $response.Content
    if ($response.Headers['Content-Type'] -like '*application/json*' -and $payload) {
        $payload = $payload | ConvertFrom-Json
    }
    [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $payload }
}

# 未登录：401 AUTH_REQUIRED；根页面：200 HTML
$dcUnauth = Invoke-DcApi GET '/api/menus'
$dcUnauth.Status
$dcUnauth.Body.code
$dcRoot = Invoke-DcApi GET '/'
$dcRoot.Status

# 登录 200；不把 Cookie/CSRF 响应打印到终端
$dcPassword = Read-Host 'DCMenu password' -MaskInput
$dcLogin = Invoke-DcApi POST '/api/auth/login' @{ password = $dcPassword }
$dcPassword = $null
$dcLogin.Status
$dcHeaders['X-CSRF-Token'] = $dcLogin.Body.csrfToken
$dcCheckSession = Invoke-DcApi GET '/api/auth/session'
$dcCheckSession.Status
$dcMeta = Invoke-DcApi GET '/api/meta'
$dcMeta.Status

# 使用独立 ID，避免与现有菜单重名
$dcId = 'accept_' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$dcMenu = @{
    schemaVersion = 1
    id = $dcId
    title = '验收菜单'
    dialogType = 'notice'
    body = @(@{ type = 'text'; text = '本机 API 验收' })
    noticeAction = @{ id = 'close'; label = '关闭'; actionType = 'close' }
}

# 校验 200，无文件写入；新建 201；读取/列表 200
$dcValidated = Invoke-DcApi POST '/api/menus/validate' @{ menu = $dcMenu }
$dcValidated.Status
$dcCreated = Invoke-DcApi POST '/api/menus' @{ menu = $dcMenu }
$dcCreated.Status
$dcFetched = Invoke-DcApi GET "/api/menus/$dcId"
$dcFetched.Status
$dcList = Invoke-DcApi GET '/api/menus'
$dcList.Status

# 非法模型 422；原菜单仍可读取，revision 不变
$dcInvalid = $dcMenu.Clone()
$dcInvalid.dialogType = 'confirmation'
$dcRejected = Invoke-DcApi PUT "/api/menus/$dcId" @{
    revision = $dcCreated.Body.revision; menu = $dcInvalid
}
$dcRejected.Status
$dcRejected.Body.code

# 更新 200；再次用旧 revision 更新应 409
$dcOldRevision = $dcCreated.Body.revision
$dcMenu.title = '修改后菜单'
$dcUpdated = Invoke-DcApi PUT "/api/menus/$dcId" @{
    revision = $dcOldRevision; menu = $dcMenu
}
$dcUpdated.Status
$dcConflict = Invoke-DcApi PUT "/api/menus/$dcId" @{
    revision = $dcOldRevision; menu = $dcMenu
}
$dcConflict.Status
$dcConflict.Body.code

# 删除 204；再次读取 404；退出 204；会话检查 401
$dcDeleted = Invoke-DcApi DELETE "/api/menus/$dcId" @{ revision = $dcUpdated.Body.revision }
$dcDeleted.Status
$dcMissing = Invoke-DcApi GET "/api/menus/$dcId"
$dcMissing.Status
$dcLogout = Invoke-DcApi POST '/api/auth/logout'
$dcLogout.Status
$dcAfterLogout = Invoke-DcApi GET '/api/auth/session'
$dcAfterLogout.Status
```

同一菜单文件摘要可在保存后检查：

```powershell
# 改成目标配置 data-directory 对应的菜单目录；在删除前执行
$dcFile = "E:/project/server26.1.2/plugins/DialogCanvas/data/menus/$dcId.yml"
$dcDiskRevision = 'sha256:' + (Get-FileHash -LiteralPath $dcFile -Algorithm SHA256).Hash.ToLowerInvariant()
$dcDiskRevision -eq $dcUpdated.Body.revision   # True
```

错误示例：

```json
{"error":"菜单已被修改，请重新读取；外部 YAML 修改需停服重载","code":"REVISION_CONFLICT"}
```

非法字段示例返回 `422 {error,code:"VALIDATION_FAILED",details:[{path:"/menu/yesButton",code:"REQUIRED",error:...}]}`。以实际 details 为准。

## 可复现完整 HTTP 验收脚本

脚本仅依赖 Python 标准库。正常模式不会控制服务器进程，输入真实管理员密码后验证登录、CRUD、并发与文件摘要；测试菜单使用唯一 ID，成功后删除。失败时保留测试数据供核查。

```powershell
python scripts/verify-stage4.py `
  --origin http://127.0.0.1:8080 `
  --menu-dir E:/project/server26.1.2/plugins/DialogCanvas/data/menus
```

同一验收实例的完整复跑命令（目录已在本轮准备，必须保持其专用测试配置；不要对原服务器使用 managed 模式）：

```powershell
$dcJava = Join-Path $env:TEMP 'DCMenu-verification/java25/jdk-25.0.4.1+1/bin/java.exe'
python scripts/verify-stage4.py `
  --origin http://127.0.0.1:18080 `
  --menu-dir E:/project/menu/build/stage4-paper/plugins/DialogCanvas/data/menus `
  --managed-paper E:/project/menu/build/stage4-paper `
  --java $dcJava `
  --browser 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' `
  --report E:/project/menu/build-reports/stage4-http-results.json
```

managed 模式使用该隔离实例的专用测试密码，不使用原服务器密码；自动正常 stop/restart。
Windows TCP 表读取在当前沙箱内被拒绝，本轮通过权限提升完成；复跑时需要允许读取 TCP 监听表。
`--browser` 可省略，省略时不会声称浏览器验证通过。已执行模式共 15 个检查点，全部通过、退出码 0。
无浏览器参数的 managed 模式为 14 项；正常模式不自动覆盖控制台、重启、监听表和关闭端口检查。

## 仍需人工验证

1. 将最终 JAR 按正常停服流程部署到原服务器，再执行 `dcmenu editor`；本轮未对其部署。
2. 在日常浏览器窗口打开输出地址，确认说明页中文可读。当前没有登录/编辑 UI；登录契约可使用上面的请求示例，不能验收尚未实现的表单。
3. 原服务器正常 stop/restart 后检查控制台与端口。保留同一浏览器会话再请求 session，应为 401；重新登录后读取保存的正式菜单应为 200。
4. 原版游戏客户端的 Dialog 展示、业务购买、前端可视化保存与游戏入口联动不属于阶段 4 Web API 验收。本轮没有用无头 Bot，也没有宣称这些链路已接入。
