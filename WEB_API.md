# DCMenu Web API v1

> 本文件是 REST 契约权威来源；服主使用入口为 [README](README.md) 与 [中文 Wiki](docs/wiki/README.md)，开发架构见 [DEVELOPER.md](DEVELOPER.md)。阶段增补以最新条目为准。

## 阶段 10.3：浏览器 YAML 导入导出（2026-10-16）

| 方法与路径 | 请求 | 成功响应 | 主要错误 |
| --- | --- | --- | --- |
| POST /api/menus/import-yaml | `Content-Type: application/yaml; charset=utf-8`，正文为 YAML 根文档，UTF-8、最多 1 MiB；不使用 multipart，不接收服务器文件路径 | 200：`{valid:true,menu,warnings,existing}`；existing 为校验时同名菜单的完整 GET 响应，或 null | 401/403、413 HTTP_413、415 UNSUPPORTED_MEDIA_TYPE、422 VALIDATION_FAILED、503 |
| POST /api/menus/export-yaml | JSON `{menu}`，当前草稿，不需要 revision | 200：`{source:"draft",fileName:"<id>-draft.yml",yaml,warnings}`；yaml 是下载内容，HTTP 响应本身为 JSON | 400 INVALID_JSON/INVALID_REQUEST、401/403、413、422 VALIDATION_FAILED、503 |

两者均受既有 Session Cookie、Origin、X-CSRF-Token 保护；HTTP 仍只监听 127.0.0.1。复用 MenuYaml、MenuValidator、MenuRepository 集合串行工作线程，不写文件、不发布注册表、不修改 metadata/revision/同步基线，不产生菜单提交审计事件。文件名仅作本地显示；ID 取自文档并校验。合法字段、null、省略及 TextSpec 简写保留；未知字段由统一 Jackson 校验拒绝。

只接受当前 `schemaVersion: 1` 的 DCMenu MenuDefinition 根文档，不接受 `{menu,revision}` 响应封装，不转换 KaMenu/其他插件或未定义的旧格式。已有旧 YAML 目录迁移是同一模型的存储迁移，不代表格式转换。安全解析沿用禁止重复键、不安全对象标签、集合别名限制、深度和大小限制；新增严格 UTF-8 校验，仅接受 JSON 兼容值。格式错误示例：

```json
{"error":"菜单校验失败","code":"VALIDATION_FAILED","details":[{"path":"/menu","code":"INVALID_YAML","error":"YAML 无法安全解析；请检查缩进、重复键、标签及编码"}]}
```

缺少 schemaVersion 使用 details.UNSUPPORTED_MENU_FORMAT；错误版本、未知字段、ID、引用和业务配置仍返回现有定位错误。有效导入在网页显示 ID、标题、校验警告及同名信息；确认只建立草稿，取消保留当前文档。失败不提供可确认文档，显示具体错误。

新建仍 POST /api/menus；同名仍 PUT /api/menus/{id}，使用预览时 captured existing.revision/serverDocument。其他标签页期间保存会导致 409 REVISION_CONFLICT；服务器 YAML 待导入/同步未知仍阻止保存。导入不重置基线，不直接覆盖服务器手动 YAML。保存顺序仍为 JSON 提交 → 注册表发布 → YAML 同步，YAML_SYNC_FAILED 部分成功语义保持。

导出明确为当前草稿：正常字段先失焦提交，非法数字或未应用的弹窗阻止导出；服务端完成同一模型、引用及业务校验。下载内容只有菜单根文档，无响应封装。等待期间文档修改/切换会丢弃旧下载结果，要求重新导出；失败不自动提交或重试写请求。需要已保存版本时先保护草稿并重新读取。

YAML 使用两空格块式排版，不保留手写注释、锚点、引号风格和原排版，不承诺字节往返。使用步骤及验收范围见 [YAML_BROWSER_TRANSFER.md](YAML_BROWSER_TRANSFER.md)。

## 阶段 10.2：统一错误提示（2026-10-16）

路由、状态码及 `{ error, code, details? }` 保持。客户端按 code 解释中文说明和建议操作；校验继续使用 details 的 JSON Pointer 定位节点与字段，未知顶层 error 原文不直接显示。REVISION_CONFLICT、YAML_SYNC_CONFLICT、MENU_REFERENCED 分别提示；成功响应 warnings.YAML_SYNC_FAILED 表示正式 JSON/注册表已提交，仍是部分成功，即使随后列表刷新失败也不能提示整个保存失败或自动重复提交。

客户端 fetch 与正文读取总限时 30 秒；网络中断、超时或无效成功响应时保留草稿，写结果不确定则先读取核查，不重试写请求。validate 是只读校验，不误报可能写入正式菜单。确认未发布/已恢复的错误码按现有服务端保证解释，MENU_STATE_UNCERTAIN 保持阻断。具体代码表、交易区别和验证边界见 [ERROR_FEEDBACK.md](ERROR_FEEDBACK.md)。没有新增 advice 响应字段或游戏预览接口。

## 阶段 8.6 联合验收（2026-10-16）

本阶段未新增路由或修改请求/响应模型、Cookie、Origin/CSRF、revision 和 JSON/YAML 同步契约。当前验收证据及实际/模拟验证边界见 [STAGE8_ACCEPTANCE.md](STAGE8_ACCEPTANCE.md)，下方阶段 4/5 记录为历史结果。

- 实际隔离 Paper 回归：除精确 POST login 外 API 继续认证；写请求同源与 CSRF 保持；改密/退出/重启失效；revision 并发一成功一 409；待导入 YAML 阻止保存和删除，重启仍保留，未覆盖文件。
- MENU_REFERENCED 仍为 409 和现有 details JSON Pointer；前端只解释这些路径并提供引用者跳转，不另扫描。跳转、取消及读取失败保护草稿，不强制/级联删除或自动重试。
- 临时预览不发送业务请求、不修改保存基线。颜色/渐变保存仍为原 TextSpec；旧保存响应不覆盖新草稿。正式保存但 YAML 导出失败的部分成功语义继续由现有 tests 回归，不改为普通全失败。
- CLI 的 list 分页不改变 GET /api/menus 的既有不分页响应；清理、恢复和审计没有新增 REST。网页没有游戏预览/命令执行/购买接口，未添加模板、历史、diff 或 WebSocket。

## 自定义物品动作增量（2026-10-15）

现有 give_item/take_item 的 material 支持 iron_ingot、minecraft:iron_ingot 及旧 IRON_INGOT。增加可选 enchantments: Record<string, integer>（等级 1～255，最多 32 项）、attributes: [{attribute, amount, operation?, slot?}]（最多 32 项，有限数值绝对值≤1000000）、unbreakable: boolean。已有 name/lore TextSpec 继续使用；属性保留原物品默认值并额外叠加。operation 缺省 ADD_NUMBER，slot 缺省 mainhand。take_item 精确匹配同一元数据原型。模型未增加外部插件物品适配器，未知注册名拒绝。

/api/meta 增加 capabilities.customActionItems、itemRegistries={enchantments:string[],attributes:string[]} 以及默认属性操作/部位。能力注册表在主线程捕获为不可变快照，HTTP 校验不访问 Bukkit。错误包括 UNKNOWN_ENCHANTMENT、DUPLICATE_ENCHANTMENT、UNKNOWN_ATTRIBUTE、INVALID_OPERATION、INVALID_SLOT，仍采用 422 VALIDATION_FAILED 和 JSON Pointer details。名称、描述、附魔及属性均作为菜单根文档保存到正式 JSON 和导出 YAML；认证、CSRF、revision、存储同步与提交语义不变。声音全能力集合继续兼容旧配置，编辑器仅展示常用子集；历史图标映射保留。


2026-10-14 实际动作接入：生产 conditionEngine/actionEngine/playerLimit 的 implemented 与 productionConnected 均为 true；增加 capabilities.economy={adapterConnected,provider} 与 serverPause=false。adapterConnected 表示适配器已安装，provider="未连接" 时不可扣款。经济提供者变化更新元数据，API 校验不执行任何交易。路由、认证、CSRF、revision 与 JSON/YAML 同步契约不变。游戏运行统一不暂停；旧 pause 保留，true 返回 SERVER_PAUSE_IGNORED warning。实际购买及恢复边界见 PRODUCTION_PURCHASE.md。

2026-10-14 画布布局补充：编辑器的物品同排文字直接使用现有 `body[].description` 文本正文（`{type:"text", text:TextSpec, width?:number}`），不是新的顶层正文类型。name/lore 仍为物品悬停信息。拖动只更新现有 body/inputs/actions 顺序；并排按钮使用 columns 和各按钮 width。没有 x/y 坐标、自由行布局或新增路由，认证、revision、JSON/YAML 同步契约保持原样。

更新：2026-10-13，阶段 5.10。范围：认证、菜单 CRUD、未保存文档校验、元数据与单 JAR 本机编辑器；没有模板、历史、diff、WebSocket 或游戏预览接口。

当前验收、复现和未验证范围见 [STAGE5_ACCEPTANCE.md](STAGE5_ACCEPTANCE.md)，手动编辑见 [YAML_EDITING.md](YAML_EDITING.md)。阶段 4 记录保留为历史证据。

阶段 4 历史验收见 [STAGE4_ACCEPTANCE.md](STAGE4_ACCEPTANCE.md)：250 项自动测试中 247 项通过、3 项既有跳过；隔离 Paper 26.1.2 Build 74 与 Edge 无头根页面检查通过，共 15 项实际检查 PASS。包含可复现的 PowerShell 请求与并发/重启脚本；原服务器尚未部署。

## 通用约定

- HTTP 固定 127.0.0.1，端口来自 config.yml。所有 /api/* 复用 AuthMiddleware，仅 POST /api/auth/login 可不带会话。
- 首页 / 与 /index.html 每次返回当前构建内容，Cache-Control=no-store，避免可复现 JAR 固定时间戳引起旧首页 ETag 误匹配和旧哈希脚本 404。
- 控制台 `dcmenu editor` 仅在监听成功后输出实际根地址。`/` 和打包于 `/web` 的前端资源允许未登录访问；当前没有前端时显示说明页。API 认证不变。Web 启动失败保留游戏测试功能，详情见 LOCAL_EDITOR.md。
- 写请求必须带同源 Origin；登录后的写请求还必须带 X-CSRF-Token。会话使用 HttpOnly、SameSite=Strict Cookie，固定 24 小时过期。详见 AUTHENTICATION.md。
- 请求体 application/json，UTF-8，大小上限 1 MiB；API 响应 no-store。JSON 拒绝重复属性、尾随文档、标量类型强制转换和小数转整数；文档最大深度 64。
- 使用统一 MenuJson.createMapper() 与现有 MenuDefinition/BodyBlock/InputDefinition/ActionDefinition 等 POJO，没有另建菜单模型。第一版 schemaVersion 必须显式为 1。
- 所有错误为 `{error, code}`；字段或引用问题可带 `details:[{path,code,error}]`。path 使用 JSON Pointer，不返回堆栈、绝对路径或内部异常消息。
- 列表按 ID 升序，第一版不分页。更新是完整替换，不支持 PATCH 或重命名。

## 认证接口

| 方法与路径 | 请求 | 成功 | 主要错误 |
| --- | --- | --- | --- |
| POST /api/auth/login | `{password}`，同源 Origin | 200，Cookie 及 authenticated/expiresAt/csrfToken | 400/401/403/415/429/503 |
| POST /api/auth/logout | Cookie、Origin、X-CSRF-Token，无请求体 | 204，撤销当前会话 | 401/403 |
| GET /api/auth/session | Cookie | 200，authenticated/expiresAt/csrfToken | 401 |

## 菜单接口

| 方法与路径 | 请求 | 成功响应 | 主要错误 |
| --- | --- | --- | --- |
| GET /api/menus | 无请求体 | 200：`{items:[{id,title,dialogType,revision,updated,warnings,syncStatus}]}` | 401/503 |
| GET /api/menus/{id} | 路径 ID | 200：`{revision,menu,warnings,syncStatus}` | 400 INVALID_MENU_ID、404 MENU_NOT_FOUND、503 |
| POST /api/menus | `{menu}` | 201：`{revision,menu,warnings,syncStatus}`，Location 指向新菜单 | 409 MENU_ALREADY_EXISTS、422、500/503 |
| PUT /api/menus/{id} | `{revision,menu}` | 200：新的 `{revision,menu,warnings,syncStatus}` | 404、409 REVISION_CONFLICT、422、428 REVISION_REQUIRED、500/503 |
| DELETE /api/menus/{id} | `{revision}` | 204，无响应体 | 404、409 REVISION_CONFLICT/MENU_REFERENCED、428、500/503 |
| POST /api/menus/validate | `{menu}`，不需要 revision | 200：`{valid:true,warnings}` | 400 INVALID_JSON、422 VALIDATION_FAILED、503 |
| GET /api/meta | 无请求体 | 200：版本、Schema、实际能力、约束、默认值、图标映射和环境目录 | 401/500 |

所有有请求体的端点同时可能返回 400 INVALID_REQUEST、413 HTTP_413、415 UNSUPPORTED_MEDIA_TYPE；认证与来源错误见认证说明。revision 类型或格式错误返回 400 INVALID_REVISION。

### 创建示例

```json
{
  "menu": {
    "schemaVersion": 1,
    "id": "welcome",
    "title": "&6欢迎",
    "dialogType": "notice",
    "afterAction": "NONE",
    "body": [{"type": "text", "text": "&f欢迎来到服务器"}],
    "noticeAction": {"id": "close", "type": "button", "label": "&a关闭", "actionType": "close"}
  }
}
```

响应为上述 menu 加上服务端管理的 metadata，并封装 revision 和 warnings：

```json
{
  "revision": "sha256:<64位小写十六进制摘要>",
  "menu": {
    "schemaVersion": 1,
    "id": "welcome",
    "title": "&6欢迎",
    "dialogType": "notice",
    "metadata": {
      "created": "2026-10-11T08:00:00Z",
      "updated": "2026-10-11T08:00:00Z",
      "version": 1,
      "author": "server_owner"
    }
  },
  "warnings": []
}
```

为简洁，响应示例省略部分正文和按钮；实际响应返回完整文档。摘要示例是占位值，不能用于实际更新。

### 更新与删除

更新提交完整菜单及最后读取的 revision。menu.id 必须等于路径 ID；metadata 可以原样回传或省略，不能自行修改。新建不能指定 metadata。服务端创建 version=1，每次更新递增；created 保留，updated 使用 UTC，author 为 server_owner。

删除使用 JSON 请求体 `{revision}`。不清理玩家数据、限购或冷却。不提供强制覆盖、强制删除或级联删除。

### 校验错误

```json
{
  "error": "菜单校验失败",
  "code": "VALIDATION_FAILED",
  "details": [{"path": "/menu/inputs/0/step", "code": "OUT_OF_RANGE", "error": "step 必须大于 0"}]
}
```

语法错误/重复属性/过深 JSON 在反序列化前返回 400；可解析文档中的模型、范围或引用错误返回 422。当前校验器遇到首个错误即可返回，不承诺一次列出所有问题。

图标未知或未映射时返回 ICON_UNMAPPED warning，允许保存并降级保留文字。仅未接生产的环境返回 BUSINESS_NOT_CONNECTED warning；当前生产已接入。缺少 Vault 经济提供者时，含经济动作的按钮返回 ECONOMY_UNAVAILABLE warning。含限购的 give_item 必须概率为 1（或省略）且不能位于 return 后，否则返回 UNSUPPORTED_LIMIT_QUANTITY，避免错误计数。validate 不执行动作、不创建文件、不发布注册表、不递增版本；会检查 open_menu 引用是否存在（自身引用例外），但不检查集合 ID 是否被占用。

## 校验与支持范围

- 展示类型：notice、confirmation、multi_action。confirmation 必须有 yesButton/noButton；multi_action 至少一个按钮。拒绝当前类型不使用的布局字段。
- 正文 text/item；输入 bool/text/number_range/single_option；按钮 custom/close/none；文字 plain/legacy/minimessage/component，兼容字符串简写。
- 校验重复按钮 ID、输入 key、单选 ID/初始选项、大小范围、有限数值、滑块范围、step、文字格式、回调次数/有效期。
- 条件校验复用 ConditionEngine.validate，完整编译五类条件及 AND/OR 嵌套，不进行玩家权限/占位符/数据求值；条件输入引用必须存在。
- 动作检查复用现有 ActionStepDefinition，delay 为非负整数 tick，chance 为 0–1；数值表达式复用 NumericExpression 的语法，引用必须为已定义 number_range 输入。
- 常量金额须为正数，常量物品数量须为正整数。动态值仍需执行时验证，不用模拟输入证明余额、购买数量或交易成功。
- 命令固定小写名称，固定参数支持选择器、文本及 JSON；校验引用、长度及控制字符，不执行命令。运行时玩家输入展开限制为安全 token，以玩家/OP/控制台身份分发，目标命令自行检查权限，见本文命令动作扩展。
- 限购为玩家维度，DAILY/WEEKLY/MONTHLY/NEVER/TIMED；TIMED 需要正数秒；冷却为非负整数秒，校验时间溢出。服务器限购尚未实现，server 只能省略或为 -1。
- 拒绝尚未实现的 dialog_list/server_links、原生模板/静态点击/additions、旧 colorMode/color。
- 材质和声音来自 Scheduler.sync 在启动阶段采集的不可变环境快照；仅非空气的现代物品材质，不接受 LEGACY_ 材质。HTTP/文件工作线程不访问 Bukkit。

## 存储、revision 和注册表

默认正式菜单路径：plugins/DialogCanvas/data/menus/<id>.json；手动入口 plugins/DialogCanvas/menus/<id>.yml，实际以 PluginConfig.dataDirectory 为准。

- JSON API 的 `menu` 直接对应正式 JSON 根菜单文档，落盘不包含 revision/menu/warnings 封装；同步 YAML 使用同一份 `MenuDefinition`。
- JsonNode → JSON 兼容 Java 值 → YAML；反向解析采用安全构造器，拒绝重复键、自定义对象标签、集合别名、隐式日期对象和过大/过深文档。字符串不会变成数值或布尔；保存前准备 JSON/YAML 序列化与有效快照。
- 只接受小写 .yml 和 1–64 位小写字母/数字/_/- 的文件 ID；拒绝 validate、Windows 保留文件名、路径分隔符、符号链接/目录目标及逃出菜单目录的路径。
- revision = 正式 JSON 原始字节的 SHA-256，独立于 metadata.version 和 schemaVersion。客户端按不透明字符串处理。
- 当前文件摘要和内存 revision 都必须匹配客户端 revision。冲突为 409，不修改文件和内存，不自动合并。
- GET/list 来自内存一致快照，不自动导入在线手工改动。外部 YAML 修改会使后续更新/删除冲突；通过控制台 dcmenu reload <id> 校验导入，不会在重启时自动覆盖。
- 第一版保存会重排 YAML，不保留注释和排版。examples/*.json 保持独立，不自动导入正式集合。
- 整个集合采用有界单工作线程串行处理，包括读、写、验证及引用检查，比同 ID 串行更严格；文件 I/O 不在 Minecraft 主线程执行。

### 保存顺序

认证/请求解析 → 写入协调器 → 模型与引用/业务校验 → revision 检查 → metadata 生成 → 验证 JSON 往返并准备 JSON/YAML 与不可变快照 → AtomicFiles 原子写入 JSON → 发布注册表 → 持久化导出意图 → 导出 YAML → 持久化同步基线 → 成功响应。

失败行为：

| 失败 | 状态与结果 |
| --- | --- |
| 校验或 revision 失败 | 422/409，文件和内存不变 |
| 原子写入失败 | 500 STORAGE_WRITE_FAILED，注册表不变 |
| 注册表发布失败且恢复成功 | 500 REGISTRY_PUBLISH_FAILED，恢复原始文件字节和旧快照；失败的新建移除新文件 |
| 文件/注册表补偿失败 | 503 MENU_STATE_UNCERTAIN，停止菜单操作，不返回成功；auth 和 meta 仍可用 |
| 文件读取失败 | 500 STORAGE_READ_FAILED，不向客户端返回内部路径 |

### 删除与引用

- 复用 MenuValidator 的引用集合及 referencePaths：noticeAction、yesButton、noButton、actions[]、exitAction 的成功 actions 与 denyActions 中的 open_menu 均参与检查。当前动作模型都是叶节点，没有可执行的嵌套动作容器；未知嵌套字段会校验失败，不把任意 JSON 递归当作动作。条件树嵌套不等于动作容器嵌套。
- 普通标题、文字、tooltip、message 及 player_data_set.value 中的字符串或形似菜单/动作的 JSON 不当作菜单引用。
- 被其他菜单引用：409 MENU_REFERENCED，details 指向 `/menus/<sourceId>/.../menuId`。先更新引用者，再删除目标。
- 自身引用允许，删除自身不因自身引用被阻止；多菜单环允许，因为校验和加载不执行导航。
- 删除检查与文件/注册表变更均留在同一个集合串行协调器内；引用者先提交则目标删除被阻止，删除先提交则后续创建引用者因目标不存在而失败。不会在检查和删除之间插入集合内写入。
- YAML 同步检查先于 revision 比较，revision 比较先于引用阻断，保留既有错误优先级及冲突保护。MENU_REFERENCED 时不改文件或注册表，不提供强制/级联删除。
- 在协调器内检查 revision 和引用，准备移除后的快照，将正式文件原子改名为同目录 .dcmenu-delete-<uuid>.tmp，再移除对应 YAML 和基线并发布注册表。
- 任一删除或发布步骤失败：补偿恢复 JSON/YAML/基线及旧快照，返回 500 STORAGE_DELETE_FAILED；恢复失败进入 503 MENU_STATE_UNCERTAIN。
- 临时删除文件不参与集合扫描；清理失败记录 WARN，残留可在停服核查后清理。它只是操作中间文件，不是历史存储。

阶段 8.2（2026-10-15）沿用原错误结构，无新增响应字段。示例：

```json
{
  "error": "请先移除其他菜单对该菜单的引用",
  "code": "MENU_REFERENCED",
  "details": [
    {"path": "/menus/home/actions/0/actions/1/menuId", "code": "MENU_REFERENCE", "error": "该菜单仍被引用"},
    {"path": "/menus/home/actions/0/denyActions/0/menuId", "code": "MENU_REFERENCE", "error": "该菜单仍被引用"},
    {"path": "/menus/shop/noticeAction/denyActions/0/menuId", "code": "MENU_REFERENCE", "error": "该菜单仍被引用"}
  ]
}
```

前端按来源菜单 ID 分组、按完整 path 去重；中文位置编号从 1 开始，原 JSON Pointer 仍是从 0 开始的索引。弹窗可跳转引用者，沿用未保存修改确认；取消或读取失败保留当前草稿。不承诺跳转时引用仍存在，也不自动重试删除；先保存引用者，再返回目标重新删除。旧响应缺少 details 时只显示未定位提示。复现及证据见 [MENU_REFERENCES.md](MENU_REFERENCES.md)。

## 启动与关闭

### 阶段 5.10 JSON/YAML 编辑同步

- 正式菜单文件为 `data/menus/<id>.json`，由启动加载、REST API 和内存注册表共同使用；文件内容是裸 `MenuDefinition`，不包含 API 响应封装。
- 手动编辑文件为 `menus/<id>.yml`。网页保存先原子写入正式 JSON、发布注册表，再导出规范化 YAML；YAML 导出失败时响应保留 `YAML_SYNC_FAILED` 警告，表示正式 JSON 已保存。
- 控制台 `dcmenu reload <id>` 校验并导入单个 YAML；`dcmenu reload` 逐项导入并返回成功/失败结果，部分成功不会伪装成整体原子提交。校验失败、ID 不匹配或写入失败保留上一份 JSON 和注册表。
- 导入成功后重新写入 JSON、发布注册表，再规范化 YAML。重启仍以正式 JSON 为准；未导入 YAML 由服主明确执行 reload，不会自动覆盖。
- YAML 首版使用清晰块状排版，不保留手写注释。该入口不扩展模板、历史、diff 或 WebSocket。

- 启动异步加载全部正式菜单、验证引用，再一次性发布。任何损坏文档、ID/文件名不一致或非法路径都不会静默跳过：菜单服务保持不可用。
- 认证/HTTP 仍启动，meta 返回 menuServiceReady=false，菜单接口返回 503；需停服检查后重启。
- 关闭停止接收菜单工作，已开始的提交完成，排队未开始工作拒绝；在后台排空后关闭存储，不在主线程等待文件事务。
- 崩溃重启以正式 .json 文件为准重建内存。临时删除文件不代表仍存在的菜单。现有 AtomicFiles 不回退为非原子替换，也不额外承诺断电目录 fsync 保证。
- 成功返回表示文件与注册表均更新；响应连接断开时操作可能已提交，应 GET 核查，不盲目重试。

## /api/meta 的内容

- apiVersion/menuSchemaVersion=1。
- schemas.MenuDefinition：反射现有 POJO 的 JSON Schema，包含 $defs、多态分支和 TextSpec；隐藏本轮不支持的布局/按钮扩展字段。具体跨字段语义由服务端校验器最终判断。
- capabilities：实际实现的 CRUD/validation、三类 Dialog、两类正文、四类输入、三类按钮动作、四种文字、条件/动作引擎和玩家限购。
- implemented 与 productionConnected 分开；当前条件/事务动作/玩家限购均已接到生产按钮，productionConnected=true。economy.provider 返回当前可用的 Vault 提供者名称，否则为“未连接”；serverPause=false。gameMenuOpeningConnected=true，frontendEditor=true；全服限购、游戏预览接口、模板、历史、diff、WebSocket 均为 false。
- constraints/defaults：ID、文档/表达式/条件限制、尺寸、单位、服务器时区、删除引用规则以及项目明确的默认值。不猜 Paper 的未配置默认值。
- iconMappings 直接复用 VanillaIconResolver 固定版本目录：1423 种经固定客户端模型和图集核对的 Sprite 映射；每项返回 atlas/sprite/category/note，不再提供 thumbnail 或纹理下载。注册名输入支持原有大写名、无命名空间小写名和 minecraft: 前缀。网页只显示文字占位，游戏客户端提供纹理。不是所有 Material 都有映射，特殊模型不假装支持。materials/sounds 是主线程采集的实际环境目录。
- 不返回密码哈希、Token、玩家数据或绝对路径。

## 游戏入口与测试范围

### pause 与 afterAction 的兼容和校验

Paper 26.1.2 Build 74 默认 pause=true、afterAction=CLOSE。显式 pause=true 与 afterAction=NONE 不兼容：校验、新建、更新和 YAML 导入均返回 422 VALIDATION_FAILED，details 定位 `/menu/afterAction`，字段码 INCOMPATIBLE_DIALOG_BEHAVIOR。校验失败不提交正式文件或注册表。

旧网页菜单若 afterAction=NONE 且 pause 缺省或为 null，渲染时兼容为 pause=false，返回 LEGACY_PAUSE_DEFAULT 警告；不自动改写 JSON/YAML，不因此改变 revision。建议网页显式设置 pause=false。其他缺省组合沿用 Paper 原生默认值。新建网页菜单明确设置 pause=false。

游戏内 `/dcmenu open <id>` 异步取得正式注册表的当前版本 POJO，通过 Scheduler.sync 调用现有 DialogRenderer.show。需要 dcmenu.admin 权限（默认 OP），控制台不能用此命令给玩家打开菜单。网页正式保存成功后再次打开即可读取新版本，无需重启；已打开的 Dialog 保持原快照。手动 YAML 必须先通过 dcmenu reload <id> 导入。菜单不存在、非法 ID 或服务未就绪时明确提示。

该命令不新增游戏预览接口、不接购买生产适配器。已有 /dcmenu test/menu/shop/icons 继续读取独立 examples 文件，因此不会随正式 API 菜单修改而变化。业务按钮仍沿用现有空动作引擎，不执行购买、扣款、发货或命令。

必要验证覆盖真实回环 HTTP CRUD/认证/CSRF/404 错误保持、revision 并发冲突、YAML 往返、路径限制、未保存校验、引用删除、写入失败、发布后抛错补偿、补偿失败停用、重启加载和实际能力元数据。Bukkit 环境采集用 MockBukkit 与 Scheduler 验证。

## 同步状态与部分成功

菜单列表项和单菜单响应兼容增加 `syncStatus`：`synced`、`missing`、`unknown`、`pendingImport`、`conflict`、`syncFailed`。`missing` 表示没有手动 YAML 或基线的初始状态；`unknown` 表示无法确认基线；它们都不表示允许覆盖已有手动文件。

`409 YAML_SYNC_CONFLICT` 用于未导入 YAML、双方修改、缺失/损坏基线、未完成导出等状态。原 revision 冲突仍为 `409 REVISION_CONFLICT`；客户端保留草稿并让管理员核查，不自动重试或覆盖。

JSON 与注册表已提交但导出失败仍返回 POST 201 / PUT 200，新 revision 和正式 menu，并附：

```json
{"syncStatus":"syncFailed","warnings":[{"code":"YAML_SYNC_FAILED","message":"正式 JSON 已保存，但 YAML 同步失败"}]}
```

这是成功响应的补充字段示例，实际响应仍包含 menu/revision。前端必须更新正式基线，显示“正式数据已保存，但 YAML 同步失败”；不能当作全失败而重复提交。也可能因基线无法读取显示 unknown，应以警告判断已提交。

删除检查相同同步状态，成功 204 表示正式 JSON、手动 YAML、基线和注册表均已删除。分离 YAML 前再次检查内容；删除期间检测到手动修改时取消删除、返回 409 YAML_SYNC_CONFLICT，并恢复正式菜单。其他运行中失败恢复缺失文件和旧注册表，保留期间新建/修改的手动 YAML；不无条件写回旧 YAML。补偿无法安全完成返回 503 MENU_STATE_UNCERTAIN。多文件操作的进程中断恢复与迁移规则见 YAML_EDITING.md。

验收期间修复了正式数据读写误用 YAML、导出覆盖提交期间手动修改、metadata 二次提交及前端旧响应覆盖等问题。最新结果见 STAGE5_ACCEPTANCE.md；未更新用户测试服务器 JAR。

## 快捷主菜单兼容扩展（2026-10-14）

MenuDefinition 增加可选根字段 `shortcutEnabled: boolean | null`。仅 `true` 表示快捷主菜单，缺省、null、false 均不启用；JSON/YAML 使用同一字段，不改菜单 schemaVersion。列表项兼容增加 `shortcutEnabled: boolean`，表示已提交的状态。`/api/meta.capabilities.mainMenuShortcut` 返回 `implemented: true`、`maxMenus: 1`、`gesture: "sneak_swap_hand"`、`permission: "dcmenu.shortcut"`。

同一菜单集合最多一个 true，允许初始未设置。新建、更新、校验和 YAML 导入都检查其他已提交菜单；占用时在写文件前返回 409，保留现有 JSON、YAML 和注册表：

```json
{"error":"快捷打开已由菜单“home”启用；请先取消该菜单的快捷打开并保存。","code":"SHORTCUT_MENU_CONFLICT","details":[{"path":"/menu/shortcutEnabled","code":"SHORTCUT_MENU_CONFLICT","error":"当前快捷主菜单：home"}]}
```

原路由、认证、CSRF、revision 和 YAML 同步契约保持有效。不自动转移或覆盖另一菜单的设置；先取消旧菜单并保存，再启用新菜单。前端冲突时只刷新菜单列表中的占用信息，保留当前草稿。每个提交由现有串行工作队列处理；主菜单 ID 和文档集合在同一次注册表发布中更新，发布失败一同补偿。JSON/注册表提交成功但 YAML 导出失败时，快捷设置已生效，沿用部分成功说明。

删除主菜单成功时清空入口；有未导入 YAML 等删除冲突时不改变入口。批量 YAML 导入逐项检查，不保证跨菜单切换整体原子；先单独取消原菜单并导入，再导入新主菜单。启动或迁移发现重复主菜单时拒绝发布，不任意挑选；迁移检查在备份和正式写入前完成，菜单服务保持不可用并记录冲突 ID。

游戏入口为潜行＋交换副手（默认 Shift＋F）和 `/dcmenu main`；只打开已保存注册表菜单，不增加 HTTP 预览接口，不接入生产购买链路。

## 参考

- [Javalin 官方文档](https://javalin.io/documentation)：路由、before、异常映射及 Context.future。实际签名使用固定 7.2.2 本地依赖编译验证。
- 游戏 API 基准与签名仍见 PAPER_DIALOG_API_REFERENCE.md，固定 Paper 26.1.2 Build 74。
## 命令动作扩展（2026-10-14）

复用现有 Command POJO：executor 接受 player、op、console，省略/null 默认 player。command 为固定小写名称，支持 minecraft:gamemode 或插件命名空间，不带 `/` 或参数；arguments 继续为字符串数组，不改持久化格式。

固定参数允许选择器、坐标、空格、中文、JSON，单项非空且最多 2048 字符；禁止换行、控制字符及 Unicode 行分隔符；拼接命令最长 8192 字符。$(key) 必须是已有输入，展开值匹配 `[A-Za-z0-9_.:+-]{1,128}`，不能注入选择器、空格、引号。固定 {player}/{uuid} 为点击者引用。API 校验配置和引用，不执行命令，不推断目标插件的语法。

/api/meta.capabilities.commandExecution 返回 executors、playerVariables、argumentLayout=one_per_line、permissionCheck=target_command。不返回 allowAllMarker，不维护命令允许列表。命令直接分发，由原版或目标插件判断执行身份的当前权限。OP 在主线程同步分发期间临时提升并 finally 恢复。命令失败是提交后副作用失败，不宣称命令已回滚。

## 密码修改接口扩展（2026-10-14）

`POST /api/auth/password`：认证、同源 Origin、X-CSRF-Token 均复用现有中间件。请求根对象严格包含 currentPassword/newPassword/confirmPassword 三个字符串，不接收菜单或 revision。新密码至少 8 字符、UTF-8 最多 72 字节，确认必须一致。

204：哈希已原子保存到 config.yml，内存哈希已更新，所有旧会话失效；Set-Cookie 清除当前 Cookie。不返回密码、哈希或新会话。400 INVALID_REQUEST、415 UNSUPPORTED_MEDIA_TYPE；403 CURRENT_PASSWORD_INCORRECT；422 INVALID_NEW_PASSWORD/PASSWORD_MISMATCH；401/403 同源与 CSRF 错误沿用既有契约；429 LOGIN_RATE_LIMITED；409 PASSWORD_CONFIG_CHANGED；503 PASSWORD_SAVE_FAILED/AUTH_UNAVAILABLE。错误均为 {error,code}。文件失败不修改内存密码或会话；网络结果不确定需重新登录核查，不自动重试。

`/api/meta.capabilities.passwordChange=true`。路由不改现有登录、Cookie、菜单 revision 和文件同步契约；具体安全与保存顺序见 AUTHENTICATION.md。


## 命令编辑界面兼容补充（2026-10-15）

网页显示单个完整命令框，API/菜单根文档仍使用 command（不含开头 /）与 arguments 数组。界面转换保留最终命令字符串、中文、JSON 和参数内部空格，继续遵循每段 2048/完整命令 8192 及控制字符限制。执行身份和旧菜单读取兼容，不新增接口。

## 阶段 7.2 持久化审核（2026-10-15）

保存仍为准备快照→原子 JSON→注册表→持久导出意图→条件原子 YAML→最终同步基线。JSON/注册表已提交后的导出意图、YAML 或基线失败，仍返回 201/200、新 revision/menu 与 YAML_SYNC_FAILED；不按全失败重提。恢复只使用持久摘要与已知意图，unknown、双方修改或额外手改 YAML 不自动覆盖。

正式 JSON 发布补偿前检查它仍匹配本次写入的 revision；期间外部 JSON 改变时保留文件并返回 503，不以旧数据覆盖未知变化。路由、Cookie、CSRF 和 revision 契约不变。条件写在临时文件完整写入之后、替换之前复核；外部编辑器不遵循同一锁时，不能承诺检查/rename 之间的竞争完全消除，详见 PERSISTENCE_AUDIT.md。
# 阶段 7.3 认证补充

认证路由、Cookie、revision 和 CRUD 契约不变。登录/当前密码验证对无效 Unicode 统一返回已有密码错误，新密码无效时 422 INVALID_NEW_PASSWORD；有效密码 UTF-8 上限仍为 72 字节，不静默截断。启动前检查 BCrypt 格式及 cost 4–16，生产生成 cost 12；认证配置异常不返回原文、哈希或原解析堆栈。开发代理在 Origin 改写前检查原始本机同源来源，生产 API 的 Origin/CSRF 检查继续生效。详见 AUTH_SECURITY_AUDIT.md。

