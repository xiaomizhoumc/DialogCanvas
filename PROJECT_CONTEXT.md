# 项目上下文

## 1.0.0 功能示例菜单更新（2026-10-17）

- 更新 examples/feature-menus 原十份详细中文注释 YAML，保留全部菜单/按钮/限购身份、原木买卖 1 游戏币/个、购买共享每日 128 个/2 秒冷却。正文/图标/背包上限/动作使用注册名，含小写简写；补充无缩略图编辑器、网页导入导出和实时预览的实际边界。
- README 区分首次安装和旧版升级：首次集合引导可用配套正式 JSON/同步基线；已有菜单只合并/导入 YAML 或 reload，不覆盖正式 JSON/基线、不删除额度/交易记录。本包与 JAR 内置 wiki_* 教学原件分开，不同时自动加载；游戏入口 /dcmenu open examples_home。
- PackageFeatureMenus.java 使用当前 1.0.0 JAR 和 MenuDocuments，拒绝旧版本解析器，输出 dist/DCMenu-FeatureExamples-1.0.0-Paper26.1-26.3.zip 及 .sha256，不覆盖历史 ZIP。32 个条目为说明、版本清单及十菜单三件套，不含 JAR/配置/玩家数据；逐项 YAML 原字节、JSON ID、基线与清单摘要及解析 JAR 摘要已核对。
- FeatureMenuPackTest 4 项和 WikiExamplesTest 3 项全部通过，0 失败/跳过；包含当前注册名解析、实际校验/仓库及注入经济的原木交易、共享冷却/额度与旧库存拒绝。本轮不改生产代码/前端、不重跑全量/真实 Paper/客户端/Vault/PAPI、不部署原服务器。兼容范围沿用当前 1.0.0 已有矩阵，非本轮全版本实测。
- 新 ZIP SHA-256：285bb2d74d7be9853e9061b28b5f483b0bb6d3888165dec1f0e99ca617432f98。当前 JAR 保持原样，解析器 SHA-256 为 3a4cf9b48c9bd1c7efc98ef2a30ee17b6f08f62f85fb3c48d4057e218c403de9；dist/README 登记新包，旧证据保留。

## 1.0.0 注册名输入与移除全部缩略图（2026-10-16）

- 正文物品、按钮图标、背包上限统一为注册名输入；动作给/收物品沿用原有文本输入。兼容 iron_ingot、minecraft:diamond_sword 及旧大写 Material；独立标签和图标不合并。RegistryItemField 临时预览、失焦/Enter单次提交、Esc取消、非法草稿阻止保存，均复用现有编辑状态和撤销机制。
- 移除1047张前端PNG、图片选择器/缩略图组件、重复前端图标目录及光效CSS。网页文字占位，不请求外网。后端1423项Sprite映射的键、atlas、sprite均与上份JAR一致，只移除thumbnail元数据；游戏正文ItemStack/按钮Sprite保持原有渲染路径，不凭注册名猜测特殊模型或插件私有物品。
- processResources重建构建输出的web副本，再合并保留的源资源与当前dist；Shadow排除旧web/vanilla。修复增量构建残留PNG及旧JS/CSS，未清空源资源。更新FRONTEND/TEXT/WEB_API/ITEM_CUSTOMIZATION/Wiki及生成源、第三方说明，详见[注册名说明](REGISTRY_ITEMS.md)。
- TypeScript、177前端测试、Vite、完整Gradle build通过：Java621项中617通过、4既有MockBukkit跳过、0失败。AuditLoggerTest的800条逐行刷盘在完整套件出现10秒超时，仅放宽测试等待为30秒，保留完整性断言，不改生产审计；最终完整重跑通过。
- 独立Edge生产页面+模拟REST的7项通过：实时输入、取消、单次撤销/重做、非法值拦截、背包与按钮输入、未知图标提示、保存重读、0图片/PNG/外部请求/脚本异常。截图已检查。Java真实Javalin元数据/静态资源及原生Sprite测试通过；模拟浏览器不代表真实文件持久化或交易验收。官方客户端缓存缺失，额外来源重核明确跳过。本轮不重跑真实Paper/客户端/经济/PAPI/交易或其他版本，不部署原服。
- 最新dist/DialogCanvas-1.0.0-Paper-26.1-26.3.jar，11,028,103字节，比上一份减少443,175字节（约433KiB）；SHA-256 `3a4cf9b48c9bd1c7efc98ef2a30ee17b6f08f62f85fb3c48d4057e218c403de9`。JAR web的3个文件与dist逐字节一致，0PNG；原十份中文注释教学YAML保留。验收见build-reports/registry-inputs-acceptance.json，当前交付说明与.sha256已更新。

## 1.0.0 内置首次生成的中文注释教学示例（2026-10-16）

- JAR 内置原有十份教学 YAML（含第08份橡木原木买卖商店），首次生成到 plugins/DialogCanvas/examples/menus/，附 examples/使用说明.md。生成只提供按需导入原件，不自动加载或执行业务；使用已有网页/控制台导入、模型和同步机制。
- BundledExamples 在现有初始化工作线程执行，AtomicFiles 原子写入，仅补齐缺失项，已有教学文件/使用说明均不覆盖；拒绝路径重定向和创建竞争，失败记录 EXAMPLE_INSTALL_FAILED，其他初始化继续。不改变正式 JSON、手动 YAML、基线、玩家数据或交易链。
- examples/wiki 源文件逐字段增加中文用途、单位、输入引用、实际影响等注释；Gradle原字节打入JAR，教学文件生成保留注释。生成器 scripts/wiki-example-comments.py/build-wiki.py 保持教程代码块与原件一致。使用说明见 [BUNDLED_EXAMPLES.md](BUNDLED_EXAMPLES.md)。
- 完整 Gradle build 最终通过：621 项，617 通过、4 既有 MockBukkit 跳过、0失败；新增生成保护5项、示例往返3项及审计11项均通过。过程中一次测试输出目录缺失、一次审计排空超时，串行19项复测后完整重跑通过，未据此改动审计生产逻辑。1050前端文件与JAR一致，十份示例/说明逐字节一致。
- 同一新JAR在工作区隔离Paper26.1.2 Build74的6项通过：首次生成、仅教学目录、API保存同步、停服端口释放、重启保留手改原件/说明并补齐缺失项、正式JSON/YAML保留。已恢复下载缺失的工作区测试运行时；未访问原服。其他三版/真实客户端/真实经济/PAPI/交易本轮未重跑。
- 最新 dist/DialogCanvas-1.0.0-Paper-26.1-26.3.jar，11,470,444 字节，SHA-256 `481a9d03d7131dd1de4a50b1f3effeb45e2d2f3ad0549354676345fa439d18f3`；同时提供 DCMenu-WikiExamples-1.0.0.zip。DELIVERY-1.0.0.json、同名.sha256 与 dist/README.md 更新；旧版本包明确标为历史。未部署原服务器或修改其数据。

## 1.0.0 版本重新打包（2026-10-16）

- 根项目和前端 package 版本更新为 1.0.0，Shadow 固定输出 `DialogCanvas-1.0.0-Paper-26.1-26.3.jar`；内置 paper-plugin.yml 的 version 同为 1.0.0，最低 api-version 保持 26.1。
- 完整 Gradle build 通过，612 测试通过、4 MockBukkit 跳过、0 失败；web/ 1050 个资源与当前前端 dist 一致。逐 ZIP 条目比对上一轮四版本验收 JAR，仅 paper-plugin.yml 的版本值变化，生产类、依赖及前端均完全一致。
- 最新交付 `dist/DialogCanvas-1.0.0-Paper-26.1-26.3.jar`，11,432,304 字节；SHA-256 `05053ddff131bb72534506a1f051e50837e9929bb40d3029cd62058a31936be8`，同目录 .sha256 与 DELIVERY-1.0.0.json 记录。旧0.1.0包保留为验收/文档基线，dist/README.md 明确最新入口。
- 本轮未重新启动四版隔离实例，兼容依据为阶段10.5实测及运行内容相同；26.1.1 为Alpha，无独立26.1下载，其他范围限制沿用原验收。未部署或操作原服，真实客户端/经济/PAPI/交易仍待人工确认。

## 阶段 10.5：最终构建、交付及 26.1～26.3 兼容验收（2026-10-16）

- 当前交付入口 [README](README.md)、[Wiki](docs/wiki/README.md)，最终结果 [STAGE10_ACCEPTANCE.md](STAGE10_ACCEPTANCE.md)。版本 0.1.0，编译 API 仍固定 26.1.2 Build 74、最低 api-version 26.1。同一 JAR 在 Paper 26.1.1 Build 29（Alpha）、26.1.2 Build 74、26.2 Build 134、26.3 Build 194 各 15 项隔离服务端 + 5 项独立 Edge/真实 REST 通过；无独立名为 26.1 的官方下载，不承诺所有历史/预发布构建。
- TypeScript、176 前端测试、Vite、完整 Gradle build 通过；Java 616 项：612 通过、4 MockBukkit 跳过（不算通过）。10 份 YAML 用现有生产解析/校验/仓库及四版真实注册表保存验证；仅服务器构造原生 Dialog，不执行示例真实交易或玩家命令。test 输入新增 examples/wiki，防示例变化被缓存忽略；未重构生产逻辑/认证/存储。
- JAR web/ 的 1050 个文件逐字节等于当前 dist；四版真实 HTTP 资源一致，浏览器 0 外部来源/0 脚本异常。每版两次启停、登录/退出/重启失效、CSRF/Origin、revision 竞争、引用阻止删除、手改 YAML 冲突和 reload、重启保留 pendingImport 均通过；服务运行不依赖 Node/Vite/CDN。记录测试时序/编码失败和修正，不把失败/跳过冒充通过。
- 最终 JAR SHA-256：8b2357f1bff068aa97089ddd988bae8c319a0165fb5ac3b757d42f23c5b00423，11,432,304 字节；产物 dist/DCMenu-0.1.0-Stage10.5-Paper26.1-26.3.zip、dist/DialogCanvas-0.1.0.jar、独立 DCMenu-WikiExamples-0.1.0-Stage10.5.zip；哈希和大小见 dist/DELIVERY-Stage10.5.json / SHA256SUMS-Stage10.5.txt。旧 ZIP 在 dist/README.md 明确列为历史，不作为最新交付。
- 未部署、访问或修改原服务器及数据；真实原版客户端/Vault/PAPI/第三方命令/实际玩家买卖与武器效果仍为人工项。逐文件清单不含凭据、配置、玩家数据、世界或测试运行时。正常重启不代表硬断电保证。

## 阶段 10.4：中文 Wiki、README 和开发文档（2026-10-16）

- 当前服主使用文档入口登记为 [README.md](README.md) 与 [中文 Wiki 首页](docs/wiki/README.md)。Wiki 六组、31 篇章节，加首页共 32 页；覆盖安装/登录、当前网页操作、YAML、导航/商店/条件/动作、变量、限额/冷却、归档/审计、命令权限、排错和完整字段。每页相对导航、前置知识、相关与前后章节；入门页附本轮真实隔离页面截图，标明 0.1.0/日期，不虚构客户端效果。
- 新增 [DEVELOPER.md](DEVELOPER.md)，按实际代码记录固定版本、单 JAR 构建、唯一菜单模型、REST/认证/CSRF/revision/同步、线程/事务/恢复及现有 public 类和内部 SPI；明确没有稳定 SDK、自定义事件或物品/动作注册公共接口。WEB_API 仍是 REST 契约；FRONTEND/TEXT/YAML_EDITING/AUTHENTICATION 链接当前教程，历史阶段描述不作为当前界面入口。
- 独立 examples/wiki/menus/ 十份完整中文注释 YAML，从最小提示至确认、导航、四输入、图标/文字、条件、三种命令身份、商店、五周期、玩家数据；导航按编号可逐项导入，配置无真实凭据/玩家数据。安装说明明确依赖、路径、打开命令、实际影响和注释不保留。不执行示例真实买卖/命令/发货。
- 生产 MenuYaml/MenuValidator/MenuRepository 的 WikiExamplesTest 3/3 通过、0 跳过；十份全集引用、顺序导入、JSON/YAML 一致、保存、导出重读及重启均通过。静态检查相对链接/图片、全部模型字段名、权限、53 个错误码及教程原件一致。生成源 scripts/build-wiki.py/wiki-content.py/wiki-reference.py；校验 scripts/verify-wiki-docs.py，详细报告 [STAGE10_DOCUMENTATION.md](STAGE10_DOCUMENTATION.md)。
- 全新工作区隔离 Paper Build 74 7 项、独立 Edge + JAR 内页面 + 真实 REST 5 项通过，截图已查看，无脚本异常：首次密码、登录、新建/正文/校验/保存/重读、YAML 下载/导入取消、手改 YAML 冲突拒绝、控制台 reload、正式文件/注册表/基线、停服端口、重启会话失效与数据保留。脚本 verify-wiki-paper.py/verify-wiki-browser.mjs 不接受生产目录，仅操作工作区；测试密码只在内存传递且输出脱敏。
- 本轮只改文档/示例/验证，无生产功能与前端改动；未重跑前端完整测试/构建。JAR SHA-256 保持 8b2357f1bff068aa97089ddd988bae8c319a0165fb5ac3b757d42f23c5b00423。无原服务器数据访问/修改或部署。真实 Vault/PAPI 动态值、玩家命令/物品/交易及原版客户端仍待人工核实，未把配置校验当运行效果保证。

## 阶段 10.3：浏览器 YAML 导入导出（2026-10-16）

- 已有 MenuYaml、MenuValidator、MenuRepository、同步基线和控制台 reload 保持复用；只新增 POST /api/menus/import-yaml 与 POST /api/menus/export-yaml 两个只读转换入口，沿用 Session、Origin、CSRF、1 MiB 限制及集合串行协调器。HTTP 固定回环。安全解析增加严格 UTF-8，非法 YAML/重复键/不安全标签/未知字段/错误引用明确拒绝，不接受服务器路径，不以文件名推断 ID。
- 顶部“导入 YAML”选择本地文件，先显示 ID、标题、校验/警告及同名状态，确认仅建立草稿。新 ID 使用正常 POST，同名使用预览捕获的 serverDocument/revision 正常 PUT；pendingImport、unknown 和 revision 冲突保护不变，metadata 仍由服务器维护。确认替换保护未保存修改并清空旧文档历史；取消、切换及晚到结果不覆盖草稿，无自动保存。
- “导出草稿 YAML”先提交合法在编辑字段，拒绝非法数字和未应用弹窗，再经后端同一校验器及 MenuYaml 输出根文档，通过 Blob 下载。不写正式 JSON/YAML/注册表/基线，等待期间内容改变时不下载旧版本。仅支持 DCMenu 当前 schemaVersion: 1，不转换 KaMenu/其他插件；未知字段不静默丢弃。不保留手写注释、锚点、原排版和引号风格，不承诺字节往返。正式保存、部分成功和审计提交语义不变。
- TypeScript、前端 176 项测试、Vite 和完整离线 Gradle 构建通过；Java 613 项中 609 通过、4 项既有 MockBukkit 跳过、0 失败/错误，新增 10 项真实 Javalin HTTP 用例。独立 Edge + 最终生产 dist + 模拟 REST 的 11 项检查通过，截图已查看，覆盖预览/取消、同名 revision、新建保存、在编辑字段导出、下载重导入、非法数字/YAML/引用、超限和取消晚到结果。浏览器模拟不冒充真实后端解析；后者由 HTTP 用例覆盖。
- 1050 个前端资源及共享错误文案与 JAR 逐字节一致。工作区 dist/DialogCanvas-0.1.0.jar：11,432,304 字节，SHA-256 8b2357f1bff068aa97089ddd988bae8c319a0165fb5ac3b757d42f23c5b00423。记录 YAML_BROWSER_TRANSFER.md；更新 WEB_API.md、FRONTEND.md、YAML_EDITING.md；汇总 build-reports/yaml-transfer-results.json。Vite 仍有既有主包体积提示，构建成功。
- 本轮无原服务器部署/数据访问，无新增隔离 Paper 或原版客户端验收；需部署后人工复核真实本机会话的导入/保存/重载及游戏展示。未扩展模板、历史、diff、WebSocket 或生产交易，未重打历史菜单模块。

## 阶段 10.2：统一错误反馈（2026-10-16）

- 继续使用 {error,code,details?}、原条件与生产交易状态。唯一文案源 frontend/src/i18n/errorMessages.json 共 52 条，包含中文说明/建议操作/简短玩家提示；Gradle 将同一文件打入 feedback/errorMessages.json，前端和 Java ErrorFeedback 共用。管理端给诊断码/交易操作编号，未知顶层错误不回显原异常、敏感值或绝对路径；字段 JSON Pointer/具体校验原因及引用弹窗保持。
- 修复确认的反馈路径：顶层错误缺少分码建议；请求无超时且正文读取中断可能遗漏不确定语义；条件 FAIL 的拒绝动作链失败缺少反馈；YAML 部分成功可能被列表刷新错误遮盖；删除等待期间新编辑被成功回调清空；菜单展示/管理分发附异常原文。补齐 30 秒完整请求超时、正文/非法成功响应处理、草稿保护、部分成功合并提示与固定诊断。没有自动重试、交易重放、新 API、命令允许列表或新持久化格式。
- 仅 ROLLED_BACK 且无不确定状态称已回滚可撤销数据；REJECTED 说明未执行；RECOVERY_REQUIRED/RECOVERY_SAVE_FAILED 保留阻断和核查建议；POST_COMMIT_FAILED 明确数据已提交，后续命令/消息失败不声称扣款发货回滚。条件 ERROR 不执行成功/拒绝链、WARN 去重保持。拒绝链共享结果解释，但原 committed/rolledBack/recoveryRequired 收据及限购映射不变，临时 OP finally 语义保持。
- TypeScript、前端构建、完整 Gradle build 成功。前端 170 项全部通过，新增 14；Java 603 项中 599 通过、4 项既有 MockBukkit 跳过、0 失败/错误，新增 7 项反馈/生产拒绝分支/分发脱敏验证。原回滚/补偿失败、收据写失败、占位符 ERROR、权限及同步冲突回归通过。独立 Edge + 当前 dist + 模拟 REST 14 项通过，截图已检查；最新同一 JAR 的隔离 Paper Build 74 13 项通过，实际 API/认证/同步/revision/重启和端口保护保持。
- 说明 ERROR_FEEDBACK.md，更新 WEB_API.md、FRONTEND.md、AUDIT_LOGGING.md；汇总 build-reports/error-feedback-results.json。新增 scripts/verify-error-feedback.mjs/.py，隔离脚本 --stage10-2 独立输出，不覆盖先前阶段证据。浏览器初始断连接夹具受 Chromium 传输重试干扰，改用 CDP 离线后完整重跑；不把该夹具失败计作通过或产品自动重试。
- 导出工作区 dist/DialogCanvas-0.1.0.jar，11,428,418 字节，SHA-256：84d8c138ca7f3abaed1930d8f4fc6bb3c2fcd26f37ea7b37eeca9674e6ba86e3。1050 个前端资源及共用文案逐字节匹配。未部署/修改原服务器或数据，未重打菜单模块；未验证原版客户端、真实 Vault/PAPI 或第三方命令自身提示，待人工复核。草稿仅当前页面内存，不保证关闭/刷新恢复；客户端超时不代表服务器取消提交。

## 阶段 10.1：安全与配置收尾（2026-10-16）

- 复用阶段 7/8 审计，检查当前 11 个 API 方法/路由组合、改密、管理命令、路径与请求边界、JSON/YAML/revision 及日志保护；未重建认证或存储，未新增接口、命令允许列表或变更 player/op/console 语义。十个既有审计入口主类与 8.6 JAR 逐字节一致，并执行当前回归。
- 修复两类有复现证据的问题：保存成功后列表刷新 401 没有要求重新登录，现保留草稿并明确“正式数据已保存，请重新登录，勿重复提交”；动作/生产收尾/Dialog 失败诊断可能带出第三方异常原文，现使用固定诊断码和事务定位，不记录异常原文或 Throwable。内部失败传播、回滚和恢复记录保持，临时 OP finally 恢复。新增 3 条故障注入先复现再通过；晚到读取覆盖草稿假设未复现，没有据此修改。
- TypeScript、Vite、完整离线 Gradle build 成功；Java 596 项：592 通过、4 项既有 MockBukkit 跳过、0 失败/错误；前端 156 项全通过。独立 Edge + 当前生产 dist + 模拟 REST：会话失效/草稿/保存后 401 等 7 项、既有改密流程 7 项通过，无脚本异常，截图已查看。最新同一 JAR 隔离 Paper Build 74 两次启停 13 项通过，覆盖真实回环监听、认证/Origin/CSRF、JSON/YAML、并发 revision、pendingImport、退出和重启失效、端口释放。24 小时边界由受控时钟验证，未实等一天。
- 收尾文档 STAGE10_SECURITY_CONFIG.md；证据 build-reports/security-closeout-results.json、stage10-1-paper-results.json、security-closeout-browser-results.json；新增 verify-security-closeout.mjs/.py，旧隔离脚本增加 --stage10-1 独立输出避免覆盖历史。认证/前端/日志说明同步更新。当前页面内存草稿不等于刷新/关闭后可恢复，不写浏览器持久存储。
- 1050 个前端资源与 JAR 逐字节匹配；仅导出工作区 dist/DialogCanvas-0.1.0.jar，11,418,092 字节，SHA-256：052bf87b12a81bd2dd831754830a780845fbd0f659d53c59112ccab28f750f45。未发现本轮确认的未修复 P0；未验证真实客户端/Vault/PAPI、第三方自己的日志或硬断电。原服务器 JAR、配置和玩家数据未访问/修改，未重打历史菜单模块。

## 阶段 8.6：P1 体验提升联合验收（2026-10-16）

- 联合覆盖 8.1 实时预览、8.2 引用弹窗、8.3 玩家数据保留/归档恢复、8.4 审计和 8.5 命令，并回归阶段 7 的线程、原子持久化/同步冲突、认证、去重和占位符 ERROR 保护。本轮未发现确认的新增生产缺陷；生产代码、API、依赖和权限规则未改。
- TypeScript、Vite 及完整 Gradle build --rerun-tasks 成功，8 项构建任务重新执行。前端 156 项全部通过；Java 593 项中 589 通过、4 项既有跳过、0 失败/错误。原生 Dialog 三项与完整自定义剑默认属性一项仍因 MockBukkit 能力跳过，不计通过。当前套件包含保留 38、审计 11＋菜单审计 6、命令 27、引用 16 和阶段 7 联合交易 9 项。
- 独立 Edge + 当前生产 dist + 模拟 REST：实时预览 11、颜色 23、渐变 19、引用弹窗 9，共 62 项通过，截图已查看。覆盖选区、临时层/基线隔离、应用/取消/撤销、非法数字、列数、旧保存响应、未保存跳转和引用定位；约 3500 字符追加 10 次含两个渲染帧耗时 23.8–34.0 ms，仅代表本机实例。
- 最终同一 JAR 的隔离 Paper Build 74：stage8 三次启动/停止 19 项通过，实际覆盖控制台命令/别名冲突、认证/改密、菜单 JSON/YAML 导入、dry-run/归档/恢复、未决状态保留、审计结果/脱敏/排空；阶段 7 另一个实例两次启动/停止 13 项通过，包含回环监听、Origin/CSRF、并发 revision 冲突、pendingImport 保存/删除阻止及重启会话失效。开发 Vite 同源防护另 4 项通过。轮转/磁盘失败、交易竞争和临时 OP 恢复由当前故障注入/MockBukkit 测试验证，不冒充真实客户端交易。
- 验证环境首次发生隔离浏览器启动超时、esbuild spawn EPERM、测试进程端口查询拒绝访问；依权限流程重跑全部通过，未放宽安全规则。未连接原版客户端、真实 Vault/PAPI 交易，未等待完整 24 小时任务，未做硬断电实验。客户端 Dialog/快捷键/实际权限插件补全仍待人工复核，异步补全返回空和别名注册时检查的边界保持。
- 新增 STAGE8_ACCEPTANCE.md、scripts/verify-stage8-paper.py（复用原测试夹具与 hooks）、verify-stage8-acceptance.py（核对新报告时间/同一摘要/资源）。更新 FRONTEND.md、LOCAL_EDITOR.md、YAML_EDITING.md、WEB_API.md；汇总 build-reports/stage8-acceptance-results.json，明细 stage8-paper-results.json、stage7-paper-results.json、stage8-frontend-results.json 及四份浏览器结果。
- 单 JAR 1050 个前端资源逐字节匹配；导出 dist/DialogCanvas-0.1.0.jar，11,417,997 字节，SHA-256：2180397ebdecf315a533dc635184ce189d8f51850ba05d8a67f451a6fa2d8126。生产代码未变，可复现构建与 8.5 摘要一致；原服务器 JAR、配置及玩家数据均未访问/修改/清理，历史菜单 ZIP 未重打。结论为自动/浏览器/隔离验收通过、原版客户端待验收，不宣称真实购买及全部游戏画面完成验证。

## 阶段 8.5：扩展现有 ManagementCommand（2026-10-16）

- 保留 help/editor/main/open/reload、dialogcanvas 根别名及原 data 控制台维护。新增 list [页码]，从已提交注册表按 ID 排序，每页 10 个，不扫描文件；edit 共用 editor 的本机地址与网页登录保护。open <菜单ID> [完整在线玩家名]：玩家省略目标仍为自己打开，控制台必须指定目标，不接受离线排队或前缀匹配。未新增 give、物品发放或调试命令。
- 使用原 COMMANDS 生命周期注册同一 BasicCommand，在实际注册前检查 Brigadier 根节点和 CommandMap；dme 空闲才加入，被占用只提示并跳过，不接管其他插件。原 dcmenu.admin 默认 op 且兼容全部管理命令，dcmenu.shortcut 默认 true 的 main/Shift＋F 规则保持。新增默认 false 的 dcmenu.command.help/editor/list/open/reload/data；open 委派包含为任意在线玩家展示的权限，data 仍仅控制台。
- execute 复制参数后经现有 Scheduler 进入主线程；权限、在线玩家、反馈均主线程。补全仅在主线程按权限读取内存 ID 和在线名字，异步/未知来源返回空；无权限用户不获得菜单 ID。打开复用 SavedMenuOpener/DialogRenderer、版本 lease、会话和点击保护。控制台或第三方打开只在实际 Future 完成后反馈及审计，不在开始时宣称成功；离线、取消、停止、服务未就绪均有明确失败。
- 完整离线 Gradle build 成功：593 项 Java 测试，589 通过、4 项既有跳过，0 失败/错误；ManagementCommandTest 27 项，新增 14 项。覆盖分页、错误页码、在线/离线目标、细分权限与无泄露、edit、冲突、异步参数快照和补全、完成前无成功反馈、取消与不可用服务；原快捷主菜单、存储导入和 SavedMenuOpener 回归保持。
- 最终同一 JAR 的隔离 Paper 26.1.2 Build 74 三次启动/停止 15 项通过：真实控制台分页/别名/目标拒绝、本机地址且未登录仍 401、审计实际结果与脱敏、与测试插件的 dme 冲突、归档/恢复回归和正常收尾。首次验证的测试夹具编译缺少 Bungee 类型依赖，补齐夹具 classpath 后完整重跑通过；不是生产失败。没有连接游戏客户端，在线展示、真实权限插件及游戏内 Tab 需人工复核；后加载插件强制覆盖命令不在当前冲突检查保证内。
- 文档 MANAGEMENT_COMMANDS.md、LOCAL_EDITOR.md、PAPER_DIALOG_API_REFERENCE.md；证据 build-reports/commands-acceptance-results.json、commands-paper-results.json、commands-paper.log；脚本 verify-commands-paper.py、verify-commands-artifacts.py。前端未改、不重跑 TypeScript，1050 个前端资源与 JAR 逐字节匹配。导出 dist/DialogCanvas-0.1.0.jar，SHA-256：2180397ebdecf315a533dc635184ce189d8f51850ba05d8a67f451a6fa2d8126。未部署原服务器或修改其数据，历史菜单 ZIP 未重打。

## 阶段 8.4：AuditLogger（2026-10-16）

- 生产插件共用一个 AuditLogger；固定插件目录 logs/audit.log，UTF-8 JSON Lines。容量 1024 的有界队列、单后台写线程；追加前超过 10 MiB 则以时间及随机 UUID 唯一文件名原子轮转，不覆盖历史，不降级普通覆盖；不自动清理历史。文件路径、目录/符号链接/真实路径检查沿用 AtomicFiles，追加本身不用每行原子替换。
- 已接入 MenuRepository 的正式创建/更新/删除和控制台手动 YAML 导入（逐项及批量汇总）、PlayerDataRetention 的扫描/归档/恢复、AuthService 密码修改及 ManagementCommand。实际异步结果完成后记录；读接口、校验草稿、预览/连续输入不记；不重建交易账本。正式成功但 YAML 导出失败为 PARTIAL，补偿不确定为 UNCERTAIN；日志失败不更改业务结果，不促使客户端重复提交。
- 固定字段为 UTC 结果时间、事件类型、随机操作 ID、来源、操作者、目标、结果、诊断码和可选前后 revision。Web 只标记 local-admin-session，玩家只记 UUID；不记 Token/密码/密码哈希/Cookie/CSRF/菜单正文/命令参数/占位符结果/异常原文。管理命令的权限与用法拒绝有明确结果，调度拒绝不异步读取玩家身份。
- 队列满、已关闭或磁盘/轮转/锁失败均计数并报警；不静默删除记录、不盲目重试。写入发生歧义后停止追加，剩余事件逐项报告失败；异常半行重启时保留原始文件并轮转，不能假装修复。诊断 Logger 异常不会传播至已提交业务。先排空生产者，再停止审计并排空队列，沿用原 onDisable 总 30 秒有界等待；失败计数/健康状态独立于资源关闭成功。
- 完整离线 Gradle build 通过：579 项 Java 测试，575 通过、4 项既有跳过、0 失败/错误。新增 20 项：AuditLoggerTest 11、MenuAuditTest 6，以及 HTTP、归档、管理命令各 1。覆盖并发 800 条、真实 10 MiB 边界、轮转/追加故障、队列满、停服排空、截断重启、协作锁、敏感字段、日志不可用但业务成功、revision/发布补偿及取消调用方。
- 最终同一 JAR 的隔离 Paper 26.1.2 Build 74 两次启动/停止 13 项验证通过：真实 HTTP CRUD、冲突、控制台 YAML 导入、改密失败/成功及会话失效、归档恢复、重启和排空后的合法 JSON 行/敏感值排除。没有连接游戏客户端或真实 Vault/PAPI 交易；未访问/部署原服务器及其数据。前端未改，本阶段不重跑前端测试；1050 个既有前端资源与 JAR 逐字节一致。
- 说明 AUDIT_LOGGING.md；证据 build-reports/audit-acceptance-results.json、audit-paper-results.json、audit-paper.log；复现脚本 verify-audit-paper.py、verify-audit-artifacts.py。最新工作区导出 dist/DialogCanvas-0.1.0.jar，SHA-256：bbba4026134dee1a54228bfbd0cb4d5a9dd6c27020459bf5a29486695777a93e；未重打历史菜单 ZIP。日志不承诺防篡改、硬断电零丢失、跨文件原子提交或不合作的外部编辑器并发安全；失败策略和诊断通道均不可用时的限制见说明。

## 阶段 8.3：玩家数据保留检查与安全归档（2026-10-16）

- 规则先行文档 PLAYER_DATA_RETENTION.md，随后实现 PlayerDataRetention、LimitRetentionPlan、RetentionArchive、RetentionConfig。只移除明确过期并超过保留天数的限购条目，不删除整个玩家文件；不使用 mtime 或推测最后上线时间。玩家业务 JSON、交易意图/收据、恢复记录及备份均保留。
- NEVER、未来计数/冷却、pending、未决交易、恢复记录、未知或缺少可靠时间的数据保守跳过。成功限购原子提交时新增 resetMode/lastCommittedAt，旧数据不自动补猜。生产维护还与当前正式菜单规则核对；规则变更、孤立旧键、共享规则歧义均跳过，避免旧周期记录在规则改为永久后被清理。
- 同一 ProductionStore 交易准入门禁、LimitService UUID 队列和 MenuRepository 集合协调器保护最终操作；当前规则检查到替换之间不允许插入菜单更新。备份后重新核查交易、恢复记录、时间资格和源内容。没有玩家 YAML 缓存回写，业务缓存不动。扫描、备份和文件替换均后台执行，停服纳入原有收尾协议。
- 仅控制台：dcmenu data dry-run [玩家UUID]、dcmenu data archive [玩家UUID]、dcmenu data restore <备份UUID>。默认 enabled=false、mode=dry-run；可选就绪后启动任务和每 24 小时任务，分批让出线程。先 dry-run 核查，报告候选、保留原因及统计。
- 每次修改先独占新 UUID 备份目录，通过现有 AtomicFiles 保存并重读验证 before/after 与 SHA-256；备份失败不改源。恢复要求当前内容精确匹配备份 after，恢复前另做备份；已是 before 不重复写，新购买或手工变化拒绝覆盖。没有自动备份清理、强制恢复或交易重放，不宣称多文件原子事务。
- 完整离线 Gradle build 成功：559 项 Java 测试，555 通过、4 项既有跳过、0 失败/错误。新增保留测试 37 项、当前菜单规则保护 3 项，并覆盖生产交易联动、配置及命令。故障注入覆盖备份失败、归档/恢复冲突、时钟回拨、备份后新增恢复记录、并发交易及取消调用方。
- 隔离 Paper 26.1.2 Build 74 两次启动/停止共 9 项检查通过：默认关闭、dry-run、路径拒绝、归档、精确恢复、新修改保护、未决状态重启保留及端口释放。未部署原服务器、未连接游戏客户端或执行真实 Vault/PAPI 交易；没有实际等待完整 24 小时。前端未改，本轮不重跑前端测试；JAR 内 1050 个现有前端资源逐字节匹配。
- 报告 build-reports/retention-acceptance-results.json、retention-paper-results.json；复现见 PLAYER_DATA_RETENTION.md。最新导出 dist/DialogCanvas-0.1.0.jar，SHA-256：4e4177ea23913fa9ff4e9aedff09aafe7b94187acb8e092bc7a3926ad1916b4a。历史安装 ZIP 未重打，不代表本轮最新 JAR。大目录扫描耗时、未知记录保守保留、外部进程并发编辑及硬断电保证的边界见规则文档。

## 十菜单功能示例包（2026-10-15）

- 新增 examples/feature-menus/README.md 和 menus 下恰好 10 份详细中文注释 YAML：导航、富文本、四类输入、原木买卖、物资/自定义武器商店、确认、条件、玩家数据、三种命令身份、动作流程。只使用现有能力，没有新增生产功能或覆盖旧菜单。
- 原木买卖均为 1 游戏币/个，支持独立手输/滑块、背包动态出售上限、购买共享每日 128 个额度及 2 秒冷却、真实库存复核与成交刷新。物资店覆盖全部五种限购周期。确认框默认关闭；真实经济动作需要现有经济服务。
- scripts/PackageFeatureMenus.java 复用当前 JAR 的 MenuYaml/MenuJson 和原子写入，生成 dist/DCMenu-FeatureExamples-26.1.2.zip。包含 10 份原注释 YAML、同一批菜单的正式 JSON 和 SHA-256 同步基线，解决互相导航菜单首次集合加载；不是 30 个菜单，不包含 JAR。安装须停服备份、排除 examples_* 同名冲突、按自定义 data-directory 调整落点。入口 /dcmenu open examples_home；未启用额外快捷主菜单。
- 新增 FeatureMenuPackTest 三项，使用实际校验器、存储/仓库、生产引擎与注入经济验证覆盖、引用/同步基线、首次加载/保存/重启及实际买卖/共享额度/旧库存拒绝。完整离线 Gradle build 成功：516 项 Java 测试，512 通过、4 项既有跳过、0 失败/错误。没有修改前端，构建复用既有产物。
- ZIP 独立核对 31 项、10 份 YAML 与源文件逐字节一致、20 个 SHA-256 基线匹配；ZIP SHA-256：803157461373b79eb1a308d19234e6146fca4f82c213d431cd27dc6541af33de。没有部署原服务器，也未运行真实客户端/Vault/PAPI 交易；注入测试不能替代上线前测试服验收。注释保留及交易恢复边界详见包内安装说明。

## 阶段 8.2 后续界面修正（2026-10-15）

- open_menu 的目标标识/示例改为跨两列独占整行；输入来源提示区分直接 key 和计算式 $(key)，不再仅以滑块描述数字输入，补充 numeric 文本框的配置说明。
- TypeScript、Vite 与独立 Edge + 模拟只读 API 的 10 项界面检查通过，含 1280 窄面板标识/示例对齐、计算式、完整命令及撤销；未访问原服务器。报告 build-reports/logic-fields-browser-results.json，截图 logic-menu-reference-layout.png。本轮只改前端布局/文案，后端规则不变；下方阶段 8.2 完整验收的 JAR 哈希为该次历史产物，后续修正版以 dist 中最新构建为准。

## 阶段 8.2：删除菜单引用提示（2026-10-15）

- 复用现有 MenuRepository 删除检查、集合串行协调器及 MenuValidator.referencePaths，后端生产扫描/删除流程未改。核对 noticeAction、yesButton、noButton、actions[]、exitAction 的 actions/denyActions 均覆盖真实 open_menu。当前没有可执行的嵌套动作容器；未知嵌套字段拒绝，不递归误判普通字符串和 player_data_set.value。
- 前端仅在 409 MENU_REFERENCED 打开弹窗，按引用菜单分组并按原路径去重；中文动作位置编号从 1 开始，完整 JSON Pointer 保留。可打开引用者，跳转前保护正式及临时草稿；取消或读取失败保留当前草稿，旧响应不影响已切换菜单。修复验收发现的禁用跳转按钮导致焦点丢失，Esc/Tab/关闭正常。不提供强制/级联删除或自动重试。
- 自身引用允许删除、跨菜单引用阻止删除、revision 与 YAML 待导入冲突的规则保持。新增 16 项后端验证，包括各按钮两个分支、多引用者、自引用、误判、两个冲突及协调器竞争的两种顺序。客户端路径分组新增 3 项测试；details 格式完全沿用，无 API 扩展。
- TypeScript、Vite、完整 Gradle build 成功。前端 156 项全通过；Java 513 项中 509 通过、4 项既有跳过，0 失败/错误，不把跳过计为通过。独立 Edge + 生产 dist + 模拟 REST 的 9 项交互验收通过；已检查弹窗截图。既有回环 HTTP 测试同时回归，不把模拟接口称作实际 Paper 验收。
- WEB_API.md、FRONTEND.md 及 MENU_REFERENCES.md 已更新。汇总证据 build-reports/menu-references-results.json，浏览器明细 menu-references-browser-results.json，复现 scripts/verify-menu-references.mjs 和 scripts/verify-menu-references-artifacts.py。
- 单 JAR 内 1050 个前端资源与 dist 逐字节一致；导出 dist/DialogCanvas-0.1.0.jar，SHA-256：106288a56aea0a2b31b6cff9c9adb94db3e5e898476656f0e1d60760e335cf0b。未部署原服务器或修改其数据，未做新的隔离 Paper/实际客户端验收；原有体积/ByteBuddy 提示仍在。下方历史阶段产物摘要保留当时记录。

## 阶段 8.1：DialogPreview 实时联动（2026-10-15）

- 在现有画布上增加内存字段预览层；标题、正文、输入标签/选项、按钮 label/tooltip、合法数字尺寸和布局未失焦即联动。数字空值/非法状态保留原输入、提示并保留已提交预览；显式“恢复未设置”去掉可选值。初始输入值改为受控显示，修复既有 defaultValue/defaultChecked 不随配置更新。
- 临时字段不进入正式文档、服务端基线、revision 或 zundo。失焦/应用一次提交；Esc、选中切换、重读、全局撤销/重做、结构操作清理。关闭/退出/切换菜单保护未提交草稿；非法草稿阻止保存/校验。旧保存响应保留正在编辑的新草稿和正式对象引用。
- TextEditor 与画布/保存共用结构化 TextSpec 转换；颜色和渐变用同一事务构建临时 doc，不分发到真实 TipTap/历史。按动画帧合并更新、只复制修改路径并缓存未变文本组件。按钮图标与文本样式分离；物品 name/lore 悬停显示富文本。不执行条件、PAPI、命令、经济，不新增游戏预览 API。
- TypeScript、Vite、完整 Gradle 构建通过；前端 153 项通过（新增 16）；Java 497 项（493 通过、4 项既有跳过，不计通过）。独立 Edge + 模拟 REST 验证实时、取消、失焦、颜色/多色渐变、保存/撤销、旧响应、生命周期和长文本；浏览器报告 live-preview-browser-results.json、汇总 live-preview-results.json。说明 LIVE_PREVIEW.md、FRONTEND.md。
- 本轮不改变认证、存储、生产购买或已固定依赖，不部署原服务器、不改其数据；未连接游戏客户端，网页是近似展示。下方历史记录的产物摘要不代表本轮最新构建。
- 最终浏览器实时流程 11 项、既有渐变选区回归 19 项均通过。1050 个前端资源与单 JAR 逐字节一致；导出 dist/DialogCanvas-0.1.0.jar，SHA-256：5f6989cec5fb1940596544225b32cceec9c94be615809a6578d81f840edeb88a。本阶段没有重新生成商店安装 ZIP。

## 常用声音、图标精简与自定义商店物品（2026-10-15）

- 动作声音候选精简为 19 种，与服务端能力取交集、显示中文；既有其他有效声音保持。图标隐藏无图/不可见和不规则模型候选，保留独立二维灯笼/蜡烛；可选 1120 项、共用 1047 张 PNG，实际删除 61 张无用途贴图，JAR 内 PNG 数已验证。保留历史 Sprite 映射以兼容旧菜单，移除的网页缩略图降级占位；音效由客户端提供，没有服务端音频文件可删。
- give_item/take_item 改为注册名文本输入，直接使用固定 API Registry.ITEM 查找、ItemType.createItemStack 创建，匹配不到拒绝；无需额外接入口。不假设插件私有名称或模组物品已在 Paper 注册；没有新增第三方物品适配器，非标准混合服务端未验证。
- ItemActionDefinition 共用旧 material/amount/name/lore/glow，新增 enchantments（1～255、最多 32 项）、attributes（最多 32 项，额外加成且保留原物品默认属性）、unbreakable。名称及描述复用 TextEditor。注册名/重复附魔别名/等级/属性/部位按主线程捕获的不可变集合校验；JSON Pointer 保留定位。发货真实附魔产生光效，不恢复旧 glow 伪闪光；take_item 精确匹配元数据，继续原事务/逆序补偿。
- /api/meta 新增 customActionItems、itemRegistries 和属性默认值；认证、CSRF、revision、JSON/YAML 同步契约及生产交易次序保持。新增中文注释 examples/custom_weapon_shop.yml 与同内容 JSON，1000 游戏币、每日限购 1 把，勿在正式服无备份覆盖同名文件。
- TypeScript、Vite、完整 Gradle build 通过。前端 137 项通过；Java 497 项（493 通过、4 跳过、0 失败），包含原生 Dialog 的既有 3 跳过及 MockBukkit 未实现默认属性的 1 跳过，不计通过。固定 Paper 两次启动实际探针补足附魔/不可损坏/原版+额外属性/精确回收原型/序列化验证，并通过 13 项 HTTP/生命周期验收；示例校验与保存、自定义字段落盘、静态 JS/CSS 访问通过。
- 证据 build-reports/item-optimization-results.json、action-items-paper-results.json、item-options-frontend-results.json；使用说明 ITEM_CUSTOMIZATION.md，FRONTEND.md 与 WEB_API.md 已更新。尚未浏览器视觉验收、实际客户端战斗或真实 Vault 购买自定义武器；探针不执行玩家交易。
- 最新导出 JAR SHA-256：e84f18b3c7869ec997414a2b186a8beeeb5782f32c7504e5ffd2f56c42f6d647。未部署或修改原服务器 JAR/菜单/密码/玩家数据。下方历史摘要仍保留当时产物哈希。

## Javalin 日志发现修复（2026-10-15）

- 用户启动日志中的“缺少 logger”经 Javalin 7.2.2 字节码与固定 Paper Build 74 日志确认：Javalin 使用线程上下文 ClassLoader 的 ServiceLoader 检测，Paper 插件类加载器不暴露服务端 SLF4J provider 资源。已有实际日志实现，不能把此提示等同于整个 Web 服务启动失败。
- WebLoggingContext 仅将 SLF4J provider 资源枚举交给 LoggerFactory 所在服务端类加载器；其他类及资源继续通过插件加载器。启动后及异常时 finally 恢复工作线程原上下文；不增加 slf4j-simple、不关闭日志、不重定向 System.err。
- 针对性测试 9 项通过（日志上下文及网页承载），shadowJar 构建通过；新的隔离 Paper Build 74 实例 13 项验收通过，两次启动/关闭均无缺少 logger 或 System.out/err.print 提示。静态资源测试仍通过。未连接游戏客户端、未部署原服务器。
- 最新 JAR SHA-256：5b94d63c552d716a54442c10d1e557fc79c23165ce5f72e2cbcf78d84ef2dabf；dist 插件与商店安装 ZIP 同步更新，菜单内容与价格未修改。上方最新产物记录优先于下文历史阶段摘要。

## 阶段 7.6：P0 汇总与联合验收（2026-10-15）

- 总报告 STAGE7_AUDIT.md 汇总 7.1–7.5 的证据、当前文件行号、P0/P1/P2 影响、修复、回归与剩余边界。确认 4 类 P0：手动 YAML 覆盖、正式 JSON 补偿覆盖、迁移原备份覆盖、交易/额度持久化先后失配；当前均已修复且回归通过，无未解决的确认 P0。不把未观察到的真实凭据泄漏、认证入侵或玩家经济损失当成已发生事故。
- 新增 Stage7JointAcceptanceTest 9 项，复用生产服务、真实临时文件、MockBukkit 与注入经济/PAPI：工作线程点击→主线程条件→持久预留→主线程扣款/发货→玩家数据/额度/effectsPending 落盘→OP 命令/消息→最终收据→实际完成回调。回执由 worker 完成，最后收据挂起时重复点击仍被拒；逐阶段线程与顺序断言通过。
- 联合故障覆盖 PAPI ERROR、意图/玩家数据/检查点/最终收据写失败、离线、停止及迟到回执、OP 命令异常。保留 denyActions 错误阻断、计数/冷却、逆序补偿、恢复记录、重启阻止及 player/op/console，无命令允许列表。联合验收未发现需要新增生产代码修复的确认 P0，本轮只增验收测试/工具/文档。
- 最终完整 Gradle build 通过：485 项 Java（482 通过、3 项既有原生 Dialog Provider 跳过、0 失败/错误）；不把跳过算通过。TypeScript 与 Vite 构建通过，前端 134 项全通过，真实 Vite 开发代理 4 项通过。先执行 61 项针对性回归，再跑完整构建；显式完成回调断言加入后重跑最终完整构建。
- 最新 JAR 的全新工作区 Paper Build 74 实例 13 项通过：控制台地址/根页面、实际 127.0.0.1 TCP 监听、认证/Origin/CSRF、校验/JSON/YAML/基线/注册表、revision 并发冲突、YAML 待导入拒绝保存/删除、控制台导入、退出、两次 stop 及端口释放、重启会话失效/正式文档与待导入 YAML 保留。正常停服保持已批准的仅关闭期最多 30 秒有界等待。
- 证据：build-reports/stage7-audit-results.json、stage7-command-results.json、stage7-frontend-tests.json、stage7-paper-results.json。复现：scripts/verify-stage7-paper.py 与 scripts/verify-stage7-audit.py；历史分步报告保留历史摘要，不混作最新验证。
- dist/DialogCanvas-0.1.0.jar 与 plugin/build/libs、隔离实例同 SHA-256：b2722ea6c0f5a6fc4cf3685dc3611b8c981466d2f91f1d162332d1cad33f4686。1111 个前端资源逐字节一致；生产源码本轮无新增更改，因此 JAR 摘要与 7.5 相同。
- 验收范围：单实例持有目录、手动 YAML 与网页写入错开、正常异常/重启。外部编辑器最后检查与 rename 间不具跨进程 CAS；若要求任意并发零覆盖，该更强模式仍阻塞，需协作协议。跨插件/多文件不是 ACID，未验证硬断电或强杀；未知交易必须人工核查，不自动重放。
- 本轮未连接原版客户端/真实 Vault/PAPI/第三方命令交易，未部署或修改原服务器数据、未使用服主凭据。隔离 HTTP/生命周期不代替客户端测试；真实客户端、终端/浏览器外部泄漏及第三方内部行为仍按报告限制待核实。


## 阶段 7.5：占位符失败处理审计（2026-10-15）

- 完整调用点、分支覆盖和限制见 PLACEHOLDER_FAILURE_AUDIT.md。复用 PlaceholderApiResolver、Result 和 ConditionEngine，兼容旧 Optional/Result 入口，新增带位置的受检解析边界，防御 null Result、未经校验的成功值及 RuntimeException/LinkageError。
- 保留 FAIL/ERROR 与短路：AND 遇正常 FAIL、OR 遇 PASS 即停止；已访问的错误立即中止，!=、叶/分组 negate 和后续 OR 不得使 ERROR 通过。整树纯结构预校验不额外解析运行时来源。正常 FAIL 不 WARN；PAPI 已告警则条件引擎不重复。
- 生产条件 ERROR 不执行成功或 denyActions，不扣款、不发货、不预留限购/冷却；回执使用 conditionError 和 code/path，原子写成功才解锁。回执失败保留 pending 并跨重启阻断。旧 denied 记录不猜测改写；旧版本不识别新终态时会保守阻断。
- 消息 PAPI 仍在状态提交后解析，失败为 POST_COMMIT_FAILED，不发送残缺消息、不假称业务回滚、不自动重发。标题/正文/按钮等原文本不新增隐式 PAPI。保留严格残留百分号规则、合法扩展空串及非法数值拒绝，不引入默认 0/空串/真值。
- 日志仅已知诊断码、事务与字段位置；自定义错误原文、异常 message、玩家输入和扩展结果不进入本轮失败日志。Result.toString 不再包含值/错误原文。交易状态 WARN 与解析失败日志分别定位，非重复解析。
- 验证：完整 Gradle build 成功；476 项 Java 测试（473 通过、3 项既有 Provider 跳过、0 失败），新增 36 项用例，包括嵌套/取反/短路、日志去重脱敏、两个动作链阻断、额度未写、错误回执失败及重启。173 项解析/条件/生产/事务相关测试全部通过。
- 最新 JAR 已导出 dist/DialogCanvas-0.1.0.jar；与 plugin/build/libs 产物一致，1111 个前端资源与 frontend/dist 逐字节一致，PAPI 类未打入 JAR。可复现报告：build-reports/placeholder-audit-results.json，脚本 scripts/verify-placeholder-audit.py。
- 本轮未访问或修改原服务器数据、未部署 JAR、未运行真实 PAPI/Vault 玩家交易或客户端验证；不把注入后端/MockBukkit 测试称为真实扩展验证。


## 阶段 7.4：点击去重和生产动作入口审计（2026-10-15）

- 完整入口覆盖表与边界见 CLICK_ENTRY_AUDIT.md。正式 notice/yes/no/actions/exit 按钮仅一处 customClick 注册，成功与拒绝分支都处于同一 ClickGuard 包围内；旧测试菜单服务没有单独生产注册入口。纯关闭不调用业务引擎，none 不注册业务回调。
- 修复跨菜单同名按钮锁碰撞，使用结构化 (UUID, menuId, buttonId)，不把会话或修订号放进锁键。加入每玩家最新 Session、原 Player 对象、应用侧次数/有效期、正式注册表版本租约；新界面/退出/已知关闭/停服撤销会话，正常保存/重载/删除重建使旧版本失效。绑定背包上限前捕获源定义；已经准入的交易继续使用冻结快照。
- 保留锁直到完整异步结算，事务 API 返回隔离结果副本；补充生产同步初始化异常的 active/pending 收尾。close_menu 复用 Menus 适配器撤销会话，关闭界面不释放仍在运行的交易锁。生产玩家级入口拒绝并发，底层动作/限购按 UUID 串行，未决意图/预留跨重启阻断；ClickGuard 不是持久幂等。
- 保留 player/op/console、OP finally 恢复及现有目标插件权限，不恢复允许列表。未知原生 Esc/外部关闭没有固定 API 通知，不能宣称当前屏幕仍显示；NONE/WAIT 的有效回调仍依赖期限/次数、后续显示、退出及正式版本校验，详见审计限制。
- 验证：完整 Gradle build，440 项 Java 测试（437 通过、3 项既有 Provider 跳过、0 失败）；新增 27 项覆盖重复扣款/发货/额度、最终回执挂起、刷新/取消、不同玩家、离线补偿、关闭迟到回执及重启阻断。独立 Paper Build 74 生命周期 6 项通过，实例 JAR 与最终 JAR 摘要一致，1111 个前端资源与 dist 一致；结果见 build-reports/click-entry-audit-results.json 与 click-entry-lifecycle-results.json。未部署原服务器、未连接 Minecraft 客户端或真实 Vault/PAPI 交易；没有更新旧商店 ZIP。

## 阶段 7.3：认证与敏感信息审计（2026-10-15）

- 复用 AuthService/AuthMiddleware，完整审计表、扫描范围及人工项见 AUTH_SECURITY_AUDIT.md。首次随机密码仍仅在哈希条件原子持久化后输出，生产 BCrypt cost 12；已有哈希不覆盖，失败不发布 Web 地址。
- 修复 PluginConfig 默认文本含哈希和 YAML 解析堆栈含配置原文；加载入口校验 BCrypt 格式与 cost 4–16，无效配置拒绝启用认证且不改密码。修复无效 Unicode 的 UTF-8 替换别名；保持合法中文/Emoji 72 字节边界、不截断。
- 开发代理转发前校验原始 Host/Origin，关闭开发 CORS，保留生产 Origin/CSRF/Cookie 契约。实际临时 Vite/HTTP 验证拒绝外部或缺失写来源，同源代理仍工作。
- 验证所有 API（仅字面 POST login 免会话）、会话容量/固定到期/退出/改密/重启失效、并发改密、错误脱敏及前端内存存储；取消的登录不再留下无法交付的会话。Java 413 项（410 通过、3 原有跳过、0 失败），最终前端与产物结果见 build-reports/auth-audit-results.json。
- 脱敏扫描未发现当前服主哈希复制或字面有效 Token/初始密码输出；9 份工作区哈希均核对为隔离测试凭据。只读扫描原服务器 43 份当前/历史日志，没有匹配的凭据输出。未解密浏览器数据库或检查外部终端录制；报告不输出凭据值。只读原服务配置用于对照，未改服主密码、服务器文件或部署 JAR。前端 TypeScript、16 项认证/开发代理测试、4 项真实 Vite 请求验证均通过。

## 阶段 7.2：文件写入和持久化一致性审计（2026-10-15）

- 完整调用点、提交/补偿/恢复表和未覆盖边界见 PERSISTENCE_AUDIT.md；自动清单及用例明细见 build-reports/persistence-audit-results.json。复用 AtomicFiles，没有另建写入器。所有正式完整替换仍严格 ATOMIC_MOVE，不能静默普通覆盖。
- AtomicFiles 增加目录/祖先真实路径与目标类型检查，拒绝符号链接和 Windows junction；限购、玩家/交易/恢复 JSON、密码配置和旧测试示例读写复用。原子替换前再次核对手动文档内容；密码完整配置和迁移备份也使用条件替换。
- 修复删除期间/补偿期间覆盖新手动 YAML、发布失败补偿覆盖外部新 JSON，以及迁移中断后覆盖原备份的问题。YAML 新改文件保留；正式/基线无法安全恢复时 503 停用菜单服务。备份只创建一次、源内容复核、回执校验并兼容旧格式；已删除的迁移菜单不从旧 YAML 复活。
- 生产玩家、交易、恢复 JSON 和限购 YAML 统一检查 4 MiB 读写上限；玩家数据先落盘、后发布缓存，失败保留旧缓存。菜单正式/辅助文件仍为 1 MiB。
- 生产顺序：pending 交易意图→限购预留→状态动作→限购计数/冷却持久化→effectsPending→消息/命令等副作用→最终收据。检查点失败不执行成功副作用，不重试；最终收据失败保留 effectsPending 阻止重复交易。恢复记录和交易意图使用同一 UUID。保留主线程游戏操作与 player/op/console、OP finally 恢复；不恢复命令允许列表。
- 保留正式 JSON/注册表成功而 YAML 导出失败的 POST 201 / PUT 200 + YAML_SYNC_FAILED 语义；intent/baseline 失败与重启恢复均有针对性测试，没有重建 REST/认证或添加自动监听。
- 验证：完整 Gradle build 成功，401 项 Java 测试（398 通过、3 项原有跳过、0 失败）；本轮新增 21 项并扩展既有验证，包含真实临时目录 junction、异常注入和重建服务恢复。JAR 打包 1111 个前端资源。未验证真实 Paper/Vault/客户端交易或硬断电，未修改或清理原服务器数据、未部署 JAR。
- 边界：单文件原子替换不是多文件/跨插件 ACID，没有跨平台目录 fsync/硬断电保证；外部编辑器不参与同一锁时，最后内容检查与 rename 间的极短竞争窗口不能宣称完全消除。手动编辑避免同时网页保存/删除同一菜单，正式 JSON/基线不要运行中手改；未完成交易需核查，不自动退款/重放命令/清理记录。

## 余额不足提示与完整命令输入（2026-10-15）

- 经济适配器对确认的余额不足返回类型化失败，事务结果保留原因；完整回滚后玩家显示“余额不足，无法购买。”。未知经济错误、余额变化不明和补偿失败仍分别处理，不根据任意异常文字猜测余额不足。
- 命令编辑改为单个“完整命令”框，例如 /time set day、/minecraft:gamemode survival {player}；玩家/OP/控制台单独选择，权限语义不变，不恢复允许列表。持久化仍为原 command/arguments，无需迁移旧菜单。JSON、中文和空格保留，UI 开头 / 可省略，控制字符/多条命令仍拒绝。
- 长命令输入可拉高、软换行和滚动，Enter 提交当前编辑而不执行命令；金额/物品数量计算式占整行、使用可拉高文本框，示例位于下方并左对齐；占位符等长值也可换行查看。
- 现有撤销事务复用，一次完整命令修改为一次菜单撤销。实际独立 Edge 窄窗口验证及截图见 build-reports/logic-fields-browser-results.json、logic-fields-examples.png、logic-fields-layout.png；未修改原服务端或用户浏览器。

## 成交消息与变量（2026-10-15）

- 消息动作现在解析本次点击的数值输入：`$(sell_slider)`、`$(buy_text)` 等，不再将标记直接发到聊天框。
- 金额计算复用经济动作的受限数值表达式，例如 `$(1 * $(sell_slider))`；外层表示在消息中输出计算结果，内部引用点击时已校验的数值输入。仅支持数值输入，不执行脚本，不将文本输入当算式。小数不显示无意义的尾零。
- 示例：`&6出售橡木原木 ×$(sell_slider)，获得 $(1 * $(sell_slider)) 游戏币。`；购买：`&a花费 $(1 * $(buy_text)) 游戏币，购买橡木原木 ×$(buy_text)。`
- 数值表达式在事务执行前检查；交易提交后才发送成功消息。普通文本、颜色、渐变仍使用原 TextSpec；Component 仅替换 text/extra/with 文本，不替换点击命令等元数据。
- 消息支持既有 PAPI 变量，例如 `%player_name%`、`%vault_eco_balance%`，通过现有可选 PlaceholderResolver 在主线程、状态提交后解析。相关插件及 expansion 必须可用；未替换的变量给出 WARN，消息失败明确记为 POST_COMMIT_FAILED，不重复扣款或声称已回滚。无需注册新的 PAPI expansion。
- 商店的 JSON、YAML 和同步基线已重新生成；数量及总金额使用相同点击快照和相同价格表达式。旧菜单“每个单价”的文字不会由 JAR 自动改为总金额，须更新对应消息模板。更新正式 JSON/配套 YAML/基线请先停服并备份；网页修改则按正常保存流程更新。
- 已运行自动测试；本次未部署原服务端，未连接 Minecraft 客户端或真实 Vault/PAPI 组合。

## 阶段 7.1：线程安全审计（2026-10-14）

- 完整审计表见 THREAD_SAFETY_AUDIT.md，追踪 HTTP、配置/菜单/交易/限购/认证工作线程、Future 回调、延迟动作、游戏入口和生命周期。保留 player/op/console 与 OP finally 恢复，不恢复命令允许列表。
- 修复 Build 74 异步命令树 canUse 直接读权限、Scheduler 执行回调持锁/disabled 清理拒绝、配置 I/O 主线程、生产事务收尾提前关存储与调度拒绝导致的清理/异常传播问题。显示失败向动作调用者传播；离线背包不宣称已持久回滚，保留补偿记录。
- 游戏操作显式调度；纯模型、不可变注册表、缓存快照、并发去重及认证不重复包装。启动显式 thenRunAsync，配置用纯数据 YAML，图标目录在初始化工作线程预载。
- 实际独立 Paper 发现 onDisable 返回后关闭 JAR 类加载器，直接后台 Jetty.stop 会 zip file closed。服主已确认仅关闭期间允许主线程最多 30 秒有界等待；文件/BCrypt/Jetty 仍在 worker，等待前关闭 Scheduler，结束各文件队列后才返回。正常运行禁止主线程阻塞等待。
- 通过完整 Gradle build（Java 356 项：353 通过、3 项原有跳过、0 失败）与针对性 MockBukkit/真实本机 HTTP 测试；独立 Build 74 启动/保存/停止/端口/重启验收记录见 build-reports/thread-lifecycle-results.json。最终测试统计和 JAR 摘要见 build-reports/thread-audit-artifact.json。未部署原测试服务器，未连接 Minecraft 客户端。
- 审计时检测到工作区另有数值输入/共享限购改动，保留并检查其主线程边界，修复编译多余括号，并保持缺省限购 key 向后兼容；新增商店示例旧材质 CHAIN 修为固定版本 IRON_CHAIN，保留菜单 ID 并重算同步摘要。这些功能不算本轮新增范围。

## 管理员修改密码（2026-10-14）

- 新增认证后的 POST /api/auth/password 和顶部中文弹窗，要求当前密码/新密码/确认；新密码至少 8 字符、UTF-8 最多 72 字节。复用 Cookie、Origin、CSRF、失败尝试限制和有界工作线程。
- 原子保存 BCrypt 哈希后发布内存新哈希、清空全部会话；保存失败保持旧状态，外部哈希修改拒绝覆盖，并发修改及旧密码在途登录有保护。修改后重登录，本页菜单草稿和撤销历史保留。
- 不写入明文、Token 日志或本地存储；密码变更无需重启。说明见 AUTHENTICATION.md、WEB_API.md、FRONTEND.md，验证见 build-reports/PASSWORD_CHANGE.md；未部署原服务器。

## 命令权限交由目标插件（2026-10-14，替代上轮允许列表方案）

- 根据服主确认，移除 DCMenu 命令允许列表及其校验，按玩家/OP/控制台身份直接分发。权限交给原版和目标插件，旧 actions.allowed-*-commands 不再读取，服主无需修改配置放行。
- 保留主线程执行、临时 OP 的 finally 恢复、参数/引用/控制字符校验及提交后命令语义，不改独立的菜单条件、限购、编辑器认证或管理命令权限。
- 网页提示、元数据、配置默认值及使用说明同步更新。验证见 build-reports/COMMAND_EXECUTION.md；未部署原服务器。

## 命令执行身份与参数扩展（2026-10-14）

- 复用 Command.executor，支持 player/op/console；网页中文选择、默认玩家。原版和插件命令走 Bukkit 分发，支持命名空间。OP 在主线程同步调用期间 setOp，finally 恢复原状态，等待期间不提权，不承诺异步插件继承权限。
- 参数仍为字符串数组，网页每行一个，保留固定 JSON、中文、空格、坐标、选择器；{player}/{uuid} 指向点击者，$(key) 输入限制为安全 token。固定参数拒绝控制字符，校验 2048/8192 长度限制。
- 三个身份允许列表独立，增加 allowed-op-commands 和显式 *；旧配置不自动开放权限。未允许命令的提示指出列表问题，不再误报“购买未完成”。
- 不部署原测试服务器；配置及手动测试见 LOCAL_EDITOR.md，接口见 WEB_API.md，验证见 build-reports/COMMAND_EXECUTION.md。

## 新手可视化界面与发货外观修正（2026-10-14）

- 用户要求去掉高级源码及格式下拉框，文字界面改为纯可视化，兼容 TextSpec 自动处理；复杂未知文本保护为只读，不丢失原配置。四种样式按钮增加高亮与可访问状态。
- 修复消息编辑器只占逻辑两列网格半行的问题，改为整行并适配工具栏/条件组宽度。权限、占位符、玩家数据和输入标识等有直接可见的例子及前提说明，示例不写入菜单。
- 普通发货物品不再强制附魔光效，旧 give_item.glow 字段不再用于发货；正文展示开关仍独立。修正 shop_live_test JSON/YAML 示例，新增 MockBukkit 发货回归。已发到背包的旧物品不自动更改。
- 本轮只生成本地 JAR，不部署原服务器；当前使用说明见 FRONTEND.md，验证见 build-reports/VISUAL_EDITOR_FIXES.md。
- 验证通过：104 项前端测试、49 项隔离浏览器检查、TypeScript 与完整 Gradle 构建。Java 313 项通过、3 项既有跳过；1111 个 JAR 前端资源与 dist 一致。实际动作文字宽度和样式高亮已查看截图；发货光效为 MockBukkit 验证，游戏客户端未连接。

## 阶段 6.5：颜色、渐变、预览及保存验收（2026-10-14）

- 五类必需文字字段已共用 TextEditor，编辑器/画布/窗口复用结构化解析和颜色插值。补齐按钮 tooltip 的彩色独立浮层，支持悬停与焦点，不被三栏滚动裁切；图标配置独立保留。
- 新增共享文字工作流样本、前端字段/原生颜色/保存后撤销测试，以及真实 HTTP + FileStorage + MiniMessage 集成测试。核对 JSON/YAML TextSpec、手动改色和渐变导入、冲突保护及重启保留，不改认证、存储同步或生产交易实现。
- 新增 scripts/verify-text-workflow.py 和浏览器脚本，使用独立 Paper Build 74、随机本机端口、JAR 内前端及独立 Edge，验证真实保存/控制台重载/继续编辑。原测试服务器 JAR 未更新。说明见 FRONTEND.md、TEXT.md，验收结果见 build-reports/TEXT_WORKFLOW_ACCEPTANCE.md。
- 最终验收：TypeScript、102 项前端测试、55 项模拟 API 浏览器回归、11 项真实浏览器检查及 7 项真实隔离 Paper 工作流检查通过。完整 Gradle build 通过，Java 312 项通过/3 项既有 Provider 限制跳过；JAR 内 1111 个资源与 dist 一致。JSON/YAML 使用生产解码器核对，原生颜色使用固定 Adventure 4.26.1 解析核对。Minecraft 客户端未连接，游戏字体、阴影、换行及图标独立颜色仍需人工复核。

## 阶段 6.3：多色 GradientPicker（2026-10-14）

- 复用原 gradient mark 和 TextSpec，增加可复用窗口、至少两色的新增/删除/顺序调整、Hex/色板及实时预览；色标固定均匀分布，不开放任意位置或相位。
- 按固定 Adventure 4.26.1 的 GradientTag、AbstractColorChangingTag、TextColor.lerp 核对，11 组真实解析样本与前端逐字符颜色一致。Unicode 码点计数包含空格/换行，跨样式节点共用整个范围的计数与插值。
- 已有渐变可从选区或内部光标继续编辑；窗口草稿不写菜单，应用一次事务，取消/Esc 恢复选区。单色与渐变互相替换；部分替换将未选字符固定为原 RGB，避免无意重算颜色。
- 范围标识与换行边界仅在编辑器内部，落盘仍为标准 MiniMessage gradient；相邻等色范围保持独立，保存重读可恢复。复杂/带相位配置继续保留源码。
- 显式引用锁文件内已有 @tiptap/pm 2.27.3，不升级其他依赖。使用与限制见 FRONTEND.md，验证报告见 build-reports/GRADIENT_PICKER.md；本轮不更新服务器 JAR。
- 最终验证：TypeScript、88 项前端测试、17 项渐变浏览器检查及 38 项颜色/文本转换回归通过；11 组真实 Adventure 渐变样本核对通过。完整 Gradle build 通过，Java 311 项通过/3 项既有原生 Provider 限制跳过，JAR 内 1111 个前端文件与 dist 逐字节一致。浏览器使用模拟 API，真实服务端保存及原版客户端显示尚未复核。

## 阶段 6.2：完善 TextSpec 与 TipTap 转换（2026-10-14）

- 复用 TextEditor、McColorPicker 和原 TextSpec，新增纯结构化 textConversion；不通过 HTML 解析，不持久化 HTML/TipTap 文档，没有新增依赖或菜单格式。
- 已验证 plain、legacy 16 色与两种完整 Hex、颜色清除装饰、粗体/斜体/下划线/删除线/重置；MiniMessage 支持颜色名称/Hex、装饰别名、嵌套、reset、换行及转义。实际依据为固定 Build 74 的 Adventure 4.26.1 与 TextRendererTest，不按记忆推断。
- 未修改文本保留原 TextSpec/字符串简写和原源码。格式切换先转换并检查目标往返，有损或未知内容明确拒绝，不直接重新解释源码。丰富样式及 legacy 无法安全表示的字面输入改为 MiniMessage，并提示格式变化。
- 未知标签、交互、显式负向装饰及复杂 Component 保留源码；渐变仅扩大到可确认整体边界的生成子集，避免改变插值。变量、普通符号、反斜杠、空行和 Unicode 不执行、不丢失。
- 新增删除线和选区清除样式，沿用局部/全局撤销及一次提交事务；预览使用相同解析和安全 React 节点。验证与限制见 FRONTEND.md、build-reports/TEXT_CONVERSION.md。本轮不更新服务器 JAR。
- 最终验证：TypeScript、74 项前端测试、15 项文本转换浏览器检查、23 项颜色回归通过；完整 Gradle build 通过，Java 310 项通过/3 项原生 Provider 限制跳过，JAR 内 1111 个前端文件与 dist 逐字节一致。浏览器采用模拟 API，真实游戏客户端尚未复核。

## 阶段 6.1：增强现有颜色选择器（2026-10-14）

- McColorPicker 为可复用 Portal 弹窗，复用锁定的 react-colorful；现有 TextEditor、TipTap、TextSpec 和 Zustand/zundo 不替换。16 色预设包含中文名称、准确 Java legacy 值及代码；Hex/RGB/滑块联动，颜色统一 #RRGGBB，无透明度。
- 打开前记录选区，无选区明确提示，不给整段改色。临时预览仅在弹窗中；应用/清除一次提交，取消/Esc 不修改正文或历史。清除纯色/渐变保留粗体、斜体、下划线。自生成 TextSpec 确认后保持富文本实例，外部复杂原文保护不变。
- 对本编辑器生成的规范颜色、粗体/斜体/下划线及渐变标签，仅在重新序列化与原文逐字相同时恢复原 TipTap 可视化；保存、重读、重做后可继续改色。未知标签、错误嵌套及不能无损识别的原文继续走源码，不做 HTML 转换。
- 最近 12 个颜色只存同源 localStorage 的颜色数组，按确认顺序去重，与菜单、认证和撤销隔离。弹窗焦点循环及键盘操作复用浏览器/组件语义，局部和菜单全局撤销不重复触发。
- 使用说明与事务边界见 FRONTEND.md，针对性验证见 build-reports/MC_COLOR_PICKER.md；本轮不更新测试服务器 JAR。

## 实际交易接入与服务端不暂停（2026-10-14）

- 当前生产按钮已改用 ProductionActionEngine，复用条件、事务动作、ClickGuard、LimitService 和 Scheduler；条件/动作/玩家限购的 meta.productionConnected=true。历史章节中“未接生产/NoOp”的说明仅指当时版本。
- Web 属性移除“暂停单人游戏”。旧 pause 字段可读取、保留，渲染统一 pause=false；显式 true 给出 SERVER_PAUSE_IGNORED 提醒，不再因 true+NONE 拒绝保存。
- 可选 Vault 兼容接口通过实际安装的 VaultUnlocked 2.20.2 API 核对并接入；当前真实经济提供者为 EssentialsX Economy。PAPI 内置 vaultunlocked 扩展的未格式化余额变量是 `%vaultunlocked_eco_balance%`。没有该扩展时解析失败，不绕过条件；PAPI 本轮用于条件，未新增文本变量替换。
- 所有经济/物品/玩家操作在主线程；玩家业务 JSON、交易意图/恢复记录及限购 YAML 异步原子写入。先校验输入和条件，再预留全部限购规则/检查冷却，再执行状态事务；成功按发货总数量计数，未发货动作链按一次计数。含限购的发货不支持随机概率或位于 return 后。
- 消息、命令、声音及菜单切换是提交后副作用。命令默认拒绝，需 config.yml 分别配置 player/console 的准确允许名称；拒绝选择器与不安全参数。配置修改需重启。
- 经济异常可能已经改变余额，不能伪装成无变更；结果不确定、补偿失败或进程中断保留记录并阻止该玩家重复交易。没有自动重试/自动崩溃退款；限购待处理预留与交易日志须人工核查后处理，不可直接删除记录。
- 示例 examples/shop_live_test.json 与带中文注释的 .yml：1000 × 数量、主动勾选确认、每人每日 3 把剑、成功冷却 10 秒。只导入 YAML，正式 JSON/同步基线由现有 Repository 生成。
- 本轮验证与客户端步骤见 PRODUCTION_PURCHASE.md；构建只生成本地 JAR，未更新或停止原服务器。

## 正文物品附魔光效与三栏独立滚动（2026-10-14）

- 用户确认正文物品开放附魔光效开关，按钮 Sprite 注明暂不支持；不扩展资源包方案。
- 复用已有 body.glow 和主线程 ItemMeta.setEnchantmentGlintOverride。勾选/取消分别保存 true/false，可恢复缺省；不新增真实附魔。网页仅模拟纹理遮罩色带，不改业务模型，默认原文继续保留。
- 固定 Build 74 对应 Adventure 4.26.1 ObjectContents/SpriteObjectContents 已从实际 API JAR 核对：只有 atlas/sprite，没有物品 glint 参数。按钮选择器提示限制，不写入虚假的 icon.glow。
- 三栏拥有独立固定高度纵向滚动区域，顶部工具栏保持原位，支持滚动边界隔离，原有拖拽与右键操作复用。
- TypeScript、前端 50 项测试、13 项光效/滚动浏览器检查及 19 项画布拖拽检查通过（隔离 Edge + 模拟 API）。本地 JAR 与资源验收见 build-reports/ITEM_GLINT_SCROLL.md；本轮未部署，原版客户端真实光效仍需复核。

## 预览右键菜单（2026-10-14）

- 预览组件右键可复制、剪切、粘贴、创建副本、删除及上下移动，空白分区可添加/粘贴。复用文档和稳定 ID，操作根据最新状态定位，过期菜单或已删除目标不执行。
- 页面内存剪贴板与文档/认证/历史分离，刷新或退出清空；正文/输入/按钮分区限制不变。原始配置、TextSpec、null、图标和逻辑完整复制，业务 ID/输入 key 只在重复时取唯一值。固定按钮禁止剪切/删除/覆盖。
- 文档修改各产生一个撤销点，复制/右键选择不产生历史；支持键盘菜单、Esc、外部关闭及屏幕边缘定位。不改后端 API，不执行业务动作。
- TypeScript、50 项前端测试、15 项右键浏览器检查及 19 项画布回归通过。浏览器为隔离 Edge + 模拟 API；本地 shadowJar 与资源核对结果见 build-reports/PREVIEW_CONTEXT_MENU.md，未部署服务器。

## 正式命令帮助与补全（2026-10-14）

- `/dcmenu` 无参数改为中文帮助，与 `help` 一致；公开命令为 help/editor/open/reload，保留 dialogcanvas 别名及 dcmenu.admin 权限。
- 使用固定 Paper Build 74 的 BasicCommand.execute/suggest/permission 签名。子命令及已提交的正式菜单 ID 支持前缀 Tab 补全；ID 从线程安全内存注册表获取，不扫描文件、不调用玩家 API。
- test/icons/shop/menu 停用，不再自动创建或加载示例菜单，不再给示例按钮输出调试消息；示例类、资源和现有文件保留为历史参考/测试材料。当前 NoOpActionEngine 仍未接生产业务。
- 执行及异步重载回复仍走 Scheduler.sync。重载结果改为中文逐项提示与成功/失败数量，字段错误保留路径，避免直接输出原始 JSON。
- 16 项 MockBukkit/菜单打开/HTTP 故障相关测试及本地 shadowJar 构建通过。未部署服务器，原生客户端 Tab 菜单和聊天帮助仍需人工复核。说明见 LOCAL_EDITOR.md 和 build-reports/PRODUCTION_COMMANDS.md。

## 画布拖拽、同排说明及并排按钮（2026-10-14）

- 共享 EditorDnd 覆盖侧栏和预览，支持左侧拖入合法分区、预览内同列表排序、鼠标/键盘拖动和 Esc 取消。稳定 ID 跨两种视图复用，拖拽过程中不写文档。
- 原生物品同排文字复用 BodyBlock.Item.description，后端已支持，无新业务格式/API。允许文字拖入物品右侧组合，保留 TextSpec/width 等字段；已有说明不覆盖。可无损拆回独立文字，每次组合/拆分只有一个撤销点。
- 属性面板增加 description、正文宽度、物品区域宽高；预览的 name/lore 仅作为悬停信息。ItemDialogBody Build 74 缺省区域 16×16 已核对字节码。多按钮画布提供每行数量 1–6；固定按钮类型不允许新增多动作按钮。
- 无任意 x/y 定位；正文/输入/按钮各自排序，物品和文字通过原生 description 同排，按钮通过 columns 并排。网页是近似预览，真实游戏位置仍需复核。
- TypeScript、43 项前端测试，19 项真实鼠标/键盘画布浏览器检查及 20 项原有流程检查通过（隔离 Edge + 模拟 API）。记录见 build-reports/CANVAS_LAYOUT.md；本轮不部署服务器。

## 预览区直接选择与布局修正（2026-10-14）

- 点击预览正文、输入、按钮选择对应稳定内部 ID，右侧属性及侧栏联动；点击标题返回菜单属性，支持键盘选择。仅选中，不执行业务、不修改文档、不增加撤销点。
- 特殊按钮使用对应 noticeAction/yesButton/noButton/exitAction 路径；禁止侧栏列表复制/删除操作误用于特殊按钮。缺少 icon 不显示假图标。
- 固定 Build 74 实现核对多按钮缺省 columns=2、ActionButton 缺省 width=150；预览按类型排列并居中，配置宽度保留。前端仍为近似预览，不承诺原生字体、GUI 缩放及像素位置一致。本轮不部署服务器。
- TypeScript 检查、38 项前端测试、前端生产构建和 Gradle shadowJar 通过；独立 Edge + 模拟 API 的 20 项流程检查通过（含三类菜单特殊按钮）。记录见 build-reports/PREVIEW_SELECTION.md。

## 游戏按钮图标关闭投影（2026-10-14）

- 独立 icon 经 VanillaIconResolver 生成的 Sprite 显式使用 `ShadowColor.none()`；不修改按钮文字、纹理本身或 label 中手写的 Sprite。使用固定 Adventure 4.26.1 已核对的 API。
- 针对性验证覆盖图标无投影、序列化往返和文字样式隔离；真实客户端效果需人工复核，本轮不部署服务器。

## 扩展原版纹理与搜索分类（2026-10-14）

- VanillaIconResolver 从固定版本官方资源生成目录读取 1423 种平面纹理映射（1506 种物品定义中约 94%）；保持旧 5 种映射和无效图标降级。只使用已核对的实际模型、PNG 和图集，特殊实体模型不假装支持。
- meta 的 iconMappings 兼容增加 thumbnail/category/note。前端共用 MaterialPicker：正文物品、按钮图标、逻辑物品支持中文/英文搜索、分类、分页式更多和原版 PNG。搜索/分类只改局部 UI，不写文档；选择时只改对应 material。
- PNG 与目录随 JAR 打包，public 纳入 Gradle 构建输入。运行时无需 CDN；未知材质、特殊模型、目录来源不匹配或加载失败明确降级。染色、多层、立体及动画仍是基础纹理近似。
- TypeScript、35 项前端测试、完整 Gradle build 和资源逐字节核对通过；后端 281 项中 278 通过、3 项既有 provider 跳过。浏览器与 JAR 核对见 build-reports/MATERIAL_PICKER.md。
- 本轮未部署原服务器；扩展纹理的真实客户端显示仍需人工复核。

## 编辑器中文显示与新手帮助（2026-10-14）

- 菜单类型、输入、文本格式、点击方式、条件、比较方式、动作、限购周期和同步状态改用中文显示，提交的枚举、ID、输入标识和公式保持原契约。
- 逻辑面板用“全部满足 / 满足任意一项”“条件满足后做什么”等表达；条件、权限、值来源、计算式、延迟、概率、限购和冷却旁提供 `!` 悬停/键盘帮助及示例。尚未接生产动作的提示仍明确显示。
- 网页使用固定 Minecraft 26.1.2 官方 zh_cn 名称（版本、源地址、SHA-1 随资源记录），物品、图标、画布采用同一解析层；声音有对应字幕名称时使用原版字幕。未知名称保留标识并明确提示，不猜译。运行时无需外网。
- 游戏物品正文未配置自定义 name 时继续让原生 ItemStack 跟随玩家客户端语言；网页中文名不写进自定义名称。服主自写的标签和名字不自动翻译。
- 验证记录见 build-reports/EDITOR_CHINESE.md；本轮只构建本地 JAR，不部署服务器。

## 修复正式菜单暂停配置导致显示失败（2026-10-13）

- 实际客户端报告 accept_web 在 DialogBase.build 抛错；前端旧默认 afterAction=NONE，pause 缺省，而固定 Paper Build 74 原生 pause 默认 true，两者不兼容。
- 新菜单明确 pause=false；公共属性增加 pause/afterAction 编辑及错误提示。统一保存校验拒绝显式 pause=true + NONE，错误定位 /menu/afterAction。
- 为保留已有正式菜单，渲染层将 NONE 且 pause 缺省/null 兼容为 false，校验附 LEGACY_PAUSE_DEFAULT 警告，不改写文件或 revision。其他组合保持原生默认语义。
- 自动验证与复核范围见 build-reports/DIALOG_PAUSE_FIX.md。本轮未更新原服务器 JAR、未改服务器菜单文件。

## 正式菜单游戏入口（2026-10-13）

- 用户反馈前端测试基本通过，优先接入正式菜单显示，后续再调整网页编辑器。
- 新增游戏内 `/dcmenu open <id>`：复用 dcmenu.admin 权限和命令注册，异步读取 MenuRepository 正式快照，再通过 Scheduler.sync 调用 DialogRenderer.show。保留 editor/reload/test/icons/shop/menu。
- 网页正式保存或 YAML 成功导入后，下一次打开读取最新版本，无需重启；已打开 Dialog 不自动刷新。未保存草稿不进入游戏注册表。
- meta 的 gameMenuOpeningConnected=true；gamePreview 仍为 false。生产业务按钮仍使用现有空动作引擎，不接购买、扣款、发货或命令。
- 本轮仅生成本地 JAR，不部署测试服务器。自动验证结果见 build-reports/SAVED_MENU_OPEN.md；本轮新增入口的真实游戏视觉效果需客户端复核。

## 当前阶段：5.10 编辑器、JSON/YAML 同步和单 JAR 验收（2026-10-13）

- 技术与范围保持 Java 25、Gradle 9.4.0 Kotlin DSL、固定 Paper 26.1.2 Build 74、Javalin 7.2.2、React 18、TypeScript、Tailwind、Zustand/zundo、TipTap/react-colorful、dnd-kit。仅 127.0.0.1、单管理员、文件存储、单 JAR，无数据库/RBAC。
- 前端 5.2–5.9 已接入登录、菜单 API、三栏布局、近似预览、属性/逻辑编辑、稳定 ID、拖拽和撤销。新增重新读取和删除入口。生产购买/动作链仍未接入，meta 的 implemented 与 productionConnected 区分不变；frontendEditor=true。
- 正式菜单仅保存并加载 `data/menus/<id>.json`；手动入口为插件目录 `menus/<id>.yml`。两者使用同一 MenuDefinition 根文档。同步摘要/导出意图位于 `data/menu-sync/`，旧正式 YAML 有备份和迁移回执；详情见 YAML_EDITING.md。
- 保存先准备可用快照、原子提交 JSON、发布注册表，再同步 YAML/基线。手动修改、基线未知、revision 冲突拒绝覆盖。YAML 导出失败以成功响应加 YAML_SYNC_FAILED 表达正式提交已完成；重启恢复仅按内容摘要，不猜测覆盖。
- 控制台支持 dcmenu editor、dcmenu reload <id> 及逐项批量 reload；测试菜单 test/icons/shop/menu 保留。批量导入不是多文件原子事务，每次发布引用必须有效。
- 验收修复：正式读写误用旧 YAML、提交期间 YAML 覆盖、删除补偿、metadata 二次提交、旧请求返回、保存后撤销和复杂文本/渐变保留等问题。复现和结果详见 STAGE5_ACCEPTANCE.md。
- 前端运行资源已由 Gradle 合入单 JAR；无需 Node.js、Vite 或 CDN。开发/构建需要本地 Node 依赖，依赖与 pnpm 版本锁定。
- 最新验证：TypeScript 检查、前端生产构建、27 项 Vitest 全部通过；完整 Gradle build 成功，256 项后端测试中 253 通过、3 项既有 Dialog provider 跳过、零失败。最终 JAR 与 dist 逐文件匹配，隔离 Paper/Edge 实际 20 项检查 PASS。
- 浏览器验收发现并修复旧首页缓存造成空白页：首页显式返回当前内容并使用 no-store，条件请求不按可复现 JAR 固定时间戳误判。复用旧浏览器缓存实测通过。
- 没有部署 E:/project/server26.1.2；实际验证仅使用工作区隔离 Paper 和独立无头 Edge。游戏客户端视觉检查及原服务器部署后的验收待用户授权部署。

以下为按日期保留的历史实现记录；与当前文件职责冲突时，以本节、WEB_API.md、FRONTEND.md 和 YAML_EDITING.md 为准。

## 阶段 4 Web API 完整验收（2026-10-11）

- 已核对阶段 4 认证、菜单 CRUD/校验/元数据、控制台入口与网页承载的文档及实现；验收矩阵、请求示例和人工项见 STAGE4_ACCEPTANCE.md。
- 新增 Stage4AcceptanceTest 三项真实 HTTP + 生产文件存储联合测试：非法修改不覆盖、两标签页不同内容并发仅一个提交、服务重建后菜单保留且旧会话拒绝。
- 完整 build 成功：250 项测试，247 项通过、3 项既有 MockBukkit Dialog provider 限制跳过，零失败；Web 包内 65 项全部通过。
- 实际 Paper 26.1.2 Build 74 验证在 build/stage4-paper 隔离实例完成，使用最终构建相同 JAR、独立菜单和 BCrypt 测试配置。控制台、登录/退出、CRUD/校验、并发 200+409、文件/注册表 revision 一致均通过。
- 实际 Paper 正常停止并重启后完整菜单/revision 保留，旧会话 401，新登录正常；Windows TCP 表确认 Web 仅监听 127.0.0.1:18080，两次关闭后端口可重新绑定。实例已停止。
- Edge 155.0.4283.45 独立无头实例访问实际服务器根页面，DOM 与截图确认中文说明页正常；实际检查共 15 项全部 PASS，证据位于 build-reports/stage4-http-results.json、stage4-paper.log、stage4-root.png。
- 本轮没有修改生产实现、没有扩展模板/历史/diff/WebSocket/前端；新增复现脚本 scripts/verify-stage4.py。原 E:/project/server26.1.2 未部署更新，日常浏览器人工操作、原服务器运行环境和游戏客户端仍未验收。

## 本机编辑器入口与网页承载（2026-10-11，实现时记录）

- 已增加控制台 `dcmenu editor`，复用现有 Paper 命令注册和 Scheduler.sync；既有 test/icons/shop/menu 命令保留。
- HTTP 固定绑定 127.0.0.1，端口读取配置；只有实际监听成功才输出带尾部 `/` 的本机地址，不启动浏览器、不携带凭据。
- Web 具有初始化/运行/失败/停止状态。端口占用诊断包含具体端口；启动失败清理 Web 资源，插件保持启用，游戏测试菜单不依赖 HTTP 就绪。
- 前端约定打包到 plugin/src/main/resources/web/，有 index.html 时提供同源 classpath 静态服务；无前端时根页面说明编辑器尚未安装完成。API 认证保持不变。
- 关闭服务撤销会话、排空 Repository 并停止 Jetty，释放端口。使用方法和验证范围见 LOCAL_EDITOR.md。
- 新增 4 项针对性验证通过；本轮未部署服务器、未进行真实 Paper 控制台或浏览器测试。可视化编辑器尚未实现。
- 完整 Gradle build 成功：247 项测试，244 项通过，3 项既有 MockBukkit Dialog provider 限制跳过，零失败。产物为 plugin/build/libs/DialogCanvas-0.1.0.jar。

## Web API 菜单 CRUD、校验与元数据（2026-10-11）

- 已实现 MenuRepository、MenuRegistry、MenuValidator 与七个菜单/元数据端点，复用 AuthMiddleware、MenuJson 和现有菜单 POJO。接口说明见 WEB_API.md。
- FileStorage 的 MENU 读写/删除已实现，菜单 YAML 位于配置 data-directory/menus/<id>.yml；其他存储类别的业务读写仍不在本阶段范围。
- JSON 严格解析、模型/引用/业务配置校验，主线程通过 Scheduler.sync 采集有效材质/声音，文档和 I/O 在有界单工作线程处理。
- revision 为 YAML 原始字节 SHA-256，更新/删除必须匹配文件和注册表版本；整集合串行协调以保护跨菜单引用，冲突 409，不静默覆盖。
- 保存先准备快照并验证 YAML 往返，再原子落盘并发布。发布失败恢复原文件与快照；补偿失败 503 并停止菜单操作，不返回成功。
- 删除被其他菜单通过成功/拒绝动作链 open_menu 引用时返回 409；无级联删除。文件原子改名后更新注册表，失败恢复。
- /api/meta 返回现有 POJO Schema 和实际能力；生产条件/动作/限购链路、游戏预览、模板/历史/diff/WebSocket/前端仍未接入。
- 正式 API 菜单注册表与 examples/*.json 测试菜单独立；提供 getMenuRepository().definition(id) 异步集成入口，本轮不增加游戏预览或业务购买。
- 本轮未部署服务器 JAR、未进行真实浏览器/客户端测试。
- 新增 45 项针对性测试全部通过；完整 build 成功，243 项总计、240 项通过、3 项既有 MockBukkit Dialog provider 限制跳过，零失败。认证既有 16 项全部通过。

## Web API 认证（2026-10-11）

- 已实现 AuthService 和 AuthMiddleware，详情见 AUTHENTICATION.md；本阶段不实现菜单 CRUD。
- 密码只保存 BCrypt 哈希；首次空配置生成安全随机密码，在工作线程原子持久化哈希后仅输出一次初始化密码，绕过插件日志桥接。
- 会话依用户最新要求改为创建后固定 24 小时（覆盖此前设计中的闲置/最长时限），ConcurrentHashMap 内存存储，退出立即撤销，服务/插件重启不恢复。
- Session Token 使用 HttpOnly、SameSite=Strict、Path=/api 的 Cookie；本机 HTTP 不设置 Secure 或 Domain，Token 不进入 URL、JSON 或日志。
- 除精确 POST /api/auth/login 外，所有 /api/* 认证，包括未知路径与错误方法；写请求校验同源 Origin，登录后的写请求额外校验 CSRF Token，不开放 CORS。
- 登录失败统一响应，5 次失败/分钟；BCrypt 使用独立有界工作池，onEnable 异步初始化 HTTP，完成后通过 Scheduler.sync 标记就绪。
- /health 保留；认证步骤之后已接入 /api/menus 与 /api/meta，未登录仍返回 401。
- 本轮未部署服务器 JAR、未进行真实浏览器登录；认证验证详情与范围见 AUTHENTICATION.md。
- 新增认证测试 16 项全部通过（服务 7 项、真实回环 HTTP 9 项）；完整 build 通过，198 项总计、195 项通过、3 项既有 Dialog provider 限制跳过。

## 玩家限购与冷却（2026-10-10）

- 已实现 `engine/limit/LimitService`，状态保存于插件目录 `playerdata/<uuid>.yml`，独立于通用玩家业务数据。
- 用户确认 DAILY 当地零点、WEEKLY 周一零点、MONTHLY 月初零点；TIMED 首次成功购买起算，间隔用秒；冷却成功后开始、秒为单位、跨重启保留，不随限购重置。
- 检查顺序：权限 → 条件 → 限购 → 冷却 → 执行。玩家入口通过 Scheduler.sync，文件异步原子写入，同 UUID 串行处理。
- 执行前持久化预留，明确提交后计数；明确回滚释放预留；不确定结果保留记录并拒绝后续该项购买，等待核查。详细契约见 `LIMIT_SERVICE.md`。
- LimitService 的 23 个 MockBukkit 测试与 2 个原子写入测试通过；审核补测覆盖周一、闰年二月、跨年、夏令时切换、跨零点提交、TIMED 起算与时间溢出。完整 Gradle build 成功，总计 92 项测试、89 项通过、3 项因 MockBukkit 原生 Dialog Provider 缺失而跳过。
- 当前未接入线上按钮与生产适配器，未更新测试服务器 JAR；动作引擎执行与回滚测试已在步骤 3.3 完成，见 ACTION_ENGINE.md。

## 项目名称与目标

- 插件名称：DialogCanvas。
- 简称：DCMenu。
- 开发 Paper 26.1.2 插件，目标稳定构建为 `26.1.2.build.74-stable`，内嵌 Web 编辑器。
- Paper Dialog API 常用签名与官方来源见 `PAPER_DIALOG_API_REFERENCE.md`；Gradle 依赖应固定上述完整版本号。
- 插件描述文件 `paper-plugin.yml` 的 `api-version` 使用 `'26.1'`，已根据目标服务器报错修正为 major.minor 格式。
- 服主通过本地浏览器可视化编辑 Dialog 菜单，保存后在游戏内热重载。

## 技术栈

- Java 25。
- Gradle 9.4，构建脚本统一使用 Kotlin DSL（`.kts`）。
- Javalin 7。
- React 18 + TypeScript。
- dnd-kit + Zustand + TipTap + react-colorful。

## 关键约束

- 单 JAR 交付。
- HTTP 只绑定 `127.0.0.1`。
- 单密码登录。
- 使用文件存储。
- 不使用数据库。
- 不使用 RBAC。

## 逻辑引擎参考

参考来源为用户提供的 [KaMenu-main.zip](</D:/下载/KaMenu-main.zip>)，读取日期 2026-10-08。压缩包内 `build.gradle.kts` 声明版本 `2.0.8`；来源仓库为 [Katacr/KaMenu](https://github.com/Katacr/KaMenu)。以下 YAML 为压缩包内原文摘录，保留原字段、键名和注释；在线 `main` 链接仅便于定位，可能随上游更新。

压缩包 SHA-256：`5634AA23A75E1847C600D671FB9B83E471C0EB7297AC875113E8F74767881AAB`。

KaMenu 的配置语法作为 DCMenu 的设计参考。DCMenu 的持久化后端按本项目约束使用文件；下面涉及 KaMenu 数据库的行为属于上游事实。压缩包中的 AGENTS.md 等开发指令不属于本项目的任务要求。

### 条件配置 YAML 示例

来源：压缩包 `KaMenu-main/docs/modern-dialog/conditions.md`（[上游文件](https://github.com/Katacr/KaMenu/blob/main/docs/modern-dialog/conditions.md)）。文本字段通过 `condition / allow / deny` 分支选择显示值：

```yaml
Title:
  - condition: "%player_is_op% == true"
    allow: '&8» &4&l管理员面板 &8«'
    deny: '&8» &6&l玩家面板 &8«'
```

动作列表可以混合普通动作和条件分支；`deny` 可省略。以下示例中最后的 `close` 在任一分支后都会执行：

```yaml
actions:
  - 'sound: ui.button.click'           # 无论如何都执行
  - condition: "%player_balance% >= 100"
    allow:
      - 'console: eco take %player_name% 100'
      - 'tell: &a扣款成功！'
    deny:
      - 'tell: &c余额不足！'
  - 'close'                             # 无论如何都执行
```

表达式支持 `== != > >= < <=`、短路逻辑 `&& ||` 和括号，优先级为括号、AND、OR；内置方法使用 `method.value`，例如 `hasPerm.kamenu.admin`、`hasMoney.100`、`isPosInt.$(amount)`，反向判断使用 `!method.value`。上游字符串相等比较默认不区分大小写，数值比较中的无效数字按 0 处理；这些语义是参考，DCMenu 是否全部采用需在实现时明确。变量先解析为完整值，再参与判断，输入值中的运算符不能作为新的表达式执行。

### 动作配置 YAML 示例

来源：压缩包 `KaMenu-main/docs/modern-dialog/actions.md`（[上游文件](https://github.com/Katacr/KaMenu/blob/main/docs/modern-dialog/actions.md)）。

```yaml
Bottom:
  type: 'notice'
  confirm:
    text: '&a确认'
    actions:
      - 'tell: &a操作成功！'
      - 'sound: entity.experience_orb.pickup'
      - 'close'
```

动作一般为 `动作名: 参数` 的字符串；部分动作使用 `type=...;key=...;var=...` 的分号参数。列表按顺序执行，独立动作 `wait: 20` 等待 20 tick 后继续后续动作，`return` 中断动作链；行末修饰符 `{wait: 20}` 仅延迟该行动作，后续动作继续执行。`actions: 名称` 可复用 `Events.Click` 或全局动作包。

常用动作包括 `tell / actionbar / title / sound`、`command / console`、`open / close / reset`、`money / item`、`data / gdata / tmpdata / meta`。`money` 依赖 Vault；示例里的 `%...%` 变量依赖 PlaceholderAPI 及对应扩展，`$(amount)` 是 Dialog 输入值，`{data:key}` 是 KaMenu 内置数据变量。

### 数据存储键的命名方式

来源：压缩包 `KaMenu-main/docs/data/storage.md`、`docs/modern-dialog/actions.md` 的 tmpdata 章节，以及 `src/main/kotlin/org/katacr/kamenu/DatabaseManager.kt`（[存储文档](https://github.com/Katacr/KaMenu/blob/main/docs/data/storage.md)、[存储源码](https://github.com/Katacr/KaMenu/blob/main/src/main/kotlin/org/katacr/kamenu/DatabaseManager.kt)）。

| 类别 | 读取格式 | 作用域与存储方式 |
|---|---|---|
| `data` | `{data:key}` | 按玩家 UUID 隔离的持久化键值 |
| `gdata` | `{gdata:key}` | 全局共享持久化键值 |
| `list` | `{list:key}` | 按玩家 UUID 隔离的持久化字符串列表，复用玩家数据键 |
| `glist` | `{glist:key}` | 全局持久化字符串列表，复用全局数据键 |
| `tmpdata` | `{tmpdata:key}` | 按玩家 UUID 隔离，保存值和绝对到期时间，重启后仍按原到期时间计算 |
| `meta` | `{meta:key}` | 玩家临时内存数据，退出或重载后清理 |

数据写入使用 `data: type=set/add/take/delete;key=键名;var=值`，全局写入用 `gdata:`；单纯赋值还可使用 `set-data: 键名 值`、`set-gdata: 键名 值`。列表以 JSON 字符串数组保存，并非独立的 SQL 列表表。

原示例键名包括 `vip_level`、`first_gift`、`last_day`、`diamond_amount`、`sign_day`、`sign_last_date`。上游当前文档允许 `_`，但建议新键使用字母、数字和短横线，例如 `daily-bonus`；不要把示例中的下划线误认为强制命名规则。键本身是业务名，玩家作用域由 UUID 承担，原示例没有要求给每个业务键拼接玩家名或 UUID。

以下原文展示 `first_gift` 的一次性领取标记：

```yaml
Bottom:
  type: notice
  confirm:
    text: '&a[ 领取礼包 ]'
    actions:
      - condition: "{data:first_gift} == true"
        allow:
          - 'toast: type=error;msg=已经领过;icon=barrier'
          - 'return'
        deny:
          - 'data: type=set;key=first_gift;var=`true`'
          - 'item: type=give;mats=APPLE;amount=5'
          - 'toast: type=task;msg=领取成功;icon=apple'
```

### 限购配置结构

来源：压缩包 `KaMenu-main/docs/data/storage.md` 的“每日限制购买100个钻石”示例。限购由普通条件和数据动作组合：`last_day` 记录日期，跨日重置 `diamond_amount` 为 100；后者保存剩余可购买数量，成功购买后扣减。

```yaml
Title: '&6钻石商店'

Settings:
  need_placeholder:
    - 'server'
    - 'math'

Events:
  Open:
    - condition: '{data:last_day} != %server_time_YYYYMMdd%'
      allow:
        - 'data: type=set;key=last_day;var=`%server_time_YYYYMMdd%`'
        - 'data: type=set;key=diamond_amount;var=`100`'
        - 'tell: &a欢迎进入钻石商店，今日钻石数量已补货。'
        - 'wait: 1'

Body:
  item:
    type: 'item'
    material: 'DIAMOND'
    name: '&6钻石'
  text-info:
    type: 'message'
    text: |
      &6请选择购买钻石的数量：
      &e50 金币 / 个
      &e每天最多只能购买 100 个
  remaining_amount:
    type: 'message'
    text: '&9剩余可购买数量： {data:diamond_amount}'

Inputs:
  amount:
    type:
      - condition: '{data:diamond_amount} > 0'
        allow: 'slider'
        deny: 'none'
    text: '&b购买数量：'
    min: 0
    max: '{data:diamond_amount}'
    default: 1
    format: '%s %s '

Bottom:
  type: 'confirmation'
  confirm:
    text: '&a[ 立即购买 ]'
    actions:
      - condition: '{data:diamond_amount} > 0'
        allow:
          - condition: '!isPosInt.$(amount)'
            allow:
              - 'tell: &c请输入有效的数字。'
              - 'return'
          - condition: 'hasMoney.%math_2_50*$(amount)%'
            allow:
              - 'money: type=take;num=%math_2_50*$(amount)%' # 扣除金币
              - 'item: type=give;mats=DIAMOND;amount=$(amount)' # 给予钻石
              - 'data: type=take;key=diamond_amount;var=$(amount)' # 扣除数据库中的剩余数量
              - 'tell: &a购买成功，消耗了 %math_2_50*$(amount)% 金币 购买了 钻石 x$(amount)'
            deny:
              - 'tell: &c金币不足！需要 金币 x%math_2_50*$(amount)%'
        deny:
          - 'actionbar: &c今日钻石已售罄！请明天再来。'
  deny:
    text: '&c[ 取消 ]'
    actions:
      - 'tell: &7已取消购买。'
      - 'sound: block.note_block.bass'
```

该原文在菜单打开时重置日期，并用滑块 `max` 限制数量。DCMenu 实现购买逻辑时还应在服务端点击处理阶段检查跨日状态、输入为正整数、数量不超过当前剩余额度及余额；读取、检查和扣减须按同一玩家及限购项协调执行，避免重复点击超额。示例中的 `wait: 1` 是上游异步数据写入后的等待，不能视为购买事务保证。

### 冷却配置结构

来源：压缩包 `KaMenu-main/docs/modern-dialog/actions.md` 的 tmpdata 章节。上游提供的原始示例使用 `bonus` 展示到期数据的操作原语：

```yaml
# 设置限时加成，1 天后自动清空
- 'tmpdata: type=set;key=bonus;var=`100`;ttl=1d'

# 续期并加值（键已存在时同时重置到期时间）
- 'tmpdata: type=add;key=bonus;var=`50`;ttl=12h'

# 只续期 12 小时，不改动数值
- 'tmpdata: type=addtime;key=bonus;ttl=12h'

# 提前 6 小时到期
- 'tmpdata: type=taketime;key=bonus;ttl=6h'

# 删除临时数据
- 'tmpdata: type=delete;key=bonus'
```

`tmpdata: type=set;key=键名;var=值;ttl=有效期` 写入带到期时间的玩家数据；`ttl` 的纯数字单位为秒，也支持 `1d2h30m15s`。`addtime` 延长原到期时间，`refresh` 重置为当前时间加 TTL；二者语义不同。

- `{tmpdata:key}` 返回存储值，缺失或过期时返回文本 `null`。
- `{tmpdata_time:key}` 返回剩余整数秒数，缺失或过期时为 0。
- `{tmpdata_time_format:key}` 返回可读剩余时间，例如 `1天 2时 30分 15秒`。

据此，业务冷却可组合为“判断剩余时间 → 通过时执行动作并写入 TTL → 冷却中提示剩余时间”；这是根据上游语法归纳的结构，并非仓库中的独立冷却菜单原文。DCMenu 文件存储应保存绝对到期时间，以保持重启后的剩余时间。

上游另外提供容器菜单的点击间隔配置，来源为 `KaMenu-main/docs/modern-dialog/setting.md`（[上游文件](https://github.com/Katacr/KaMenu/blob/main/docs/modern-dialog/setting.md)）：

```yaml
Settings:
  min_click_delay: 200
```

`min_click_delay` 单位为毫秒、按当前容器菜单会话记录；重新打开或 `reset` 后重新计时。该设置用于容器点击节流，不能直接当成 Dialog 按钮的持久化业务冷却。

## 存储接口与格式约定

- 插件配置、菜单和模板使用 YAML；网页 API、玩家数据和结构化历史快照使用 JSON。
- 阶段 1 使用 `JsonNode` 和通用 `StorageProvider`，提供 `load/save/delete/listIds/exists`，通过 `StorageCategory` 区分菜单、模板、历史和玩家数据。
- 阶段 2 增加类型化 Repository，保持底层存储接口不变。
- 阶段 1 的 `FileStorage` 原为存储骨架；Web API 阶段已补齐 MENU 类别业务读写，其他类别仍显式抛出 UnsupportedOperationException。
- `save` 的接口契约是返回前完成原子持久化；当前骨架没有待刷新缓存，不提供 `saveAll`。

## 测试方式

- 使用 MockBukkit。
- 不使用无头 Bot。

## 已完成的真机验证

- 2026-10-09，在 `E:\project\server26.1.2` 的 Paper 26.1.2 Build 74 + Java 25 上成功启用插件，HTTP /health 返回 200/ok。
- 用户确认 `/dcmenu test` 最小 notice 菜单测试成功；服务端日志确认多次打开及主线程 test_close 回调。
- 当前真机验证覆盖最小菜单显示与按钮回调链路；服务器仍采用最小测试回调，尚未部署事务动作引擎。
- 测试详情与日志见 `build-reports/SERVER_TEST_PROGRESS.md` 和 `server-menu-test-success.log`。

## 步骤 3.2 条件引擎实现

- 已实现 ConditionEngine，支持 permission、placeholder、player_data、numeric、string，以及 AND/OR 嵌套和 negate。
- 采用用户最新要求：数值统一转为有限 double；字符串比较区分大小写。占位符解析失败返回 false 并记录 WARN。
- 输入、占位符、玩家数据来源在单次求值内缓存；默认限制深度 32、节点数 1024，可配置。
- 条件错误不可通过 negate 或 OR 变为通过；错误码与路径通过 evaluateDetailed 返回。
- 占位符和玩家数据通过接口注入，当前未接入实际 ActionEngine 业务执行、PlaceholderAPI 或文件存储。
- MockBukkit 条件测试 39 项通过，详见 CONDITION_ENGINE.md。

## 步骤 3.3 动作引擎实现（2026-10-10）

- 已实现 TransactionalActionEngine，保留 ActionEngine 接口与异步回调，覆盖用户要求的十二类动作及 return。
- 参考以上 KaMenu 动作示例，采用用户确认规则：delay 为 tick、chance 默认 1、return 正常提交；可逆状态动作失败逆序补偿，消息/命令/声音/菜单在提交后按序执行。
- 每个动作提供 execute/rollback；不能通用撤销的副作用明确拒绝补偿，不纳入状态事务。失败补偿由注入的 RecoveryStore 保存。
- 全部玩家操作在 Scheduler.sync 主线程；同 UUID 串行，异步接口完成后切回主线程；shutdown 必须先于 Scheduler 关闭。
- 已修复 return 延迟被忽略与声音名简单替换下划线的问题；命令使用固定名称、单独参数 token、严格输入检查及注入的允许策略。
- 37 个 MockBukkit 动作测试、10 个数值表达式测试通过；完整 build 成功，总计 139 项、136 项通过、3 项既有 Dialog Provider 限制而跳过。审核补测覆盖异步补偿、缺失收据、调用方取消和延迟命令策略撤销；实际派发命令前重新校验策略。
- 引擎 shutdown 排空要求插件仍启用且 Scheduler 可用；生产接入仍需停服在途事务持久恢复与真机验证，不能把内存测试视为崩溃恢复保证。
- 经济、玩家数据、菜单、恢复日志继续通过接口注入；本轮没有接入生产适配器、线上按钮或更新测试服务器 JAR。详细契约见 ACTION_ENGINE.md。

## 步骤 3.5 点击去重（2026-10-10）

- 已实现 ClickGuard，严格按 `playerUUID + ":" + buttonId` 用 ConcurrentHashMap 原子占用锁，处理中重复点击直接忽略，不排队。
- 已接入 DialogRenderer 按钮回调；锁覆盖异步动作链与回调后关闭菜单的过程，正常/异常完成及同步抛错均通过 try-finally 释放。
- 取消返回结果 Future 不会提前解锁；不同 UUID 或不同按钮不互相阻塞。相同玩家在不同菜单使用相同 buttonId 仍共享锁，符合本轮指定键规则。
- 7 个 ClickGuard 单元测试及 1 个 MockBukkit 回调集成测试通过，完整 build 成功；总计 147 项测试、144 项通过、3 项既有 Dialog Provider 限制而跳过。
- 本轮未更新服务器 JAR；事务动作引擎生产适配器仍未安装。

## 步骤 3.6 占位符解析层

- PlaceholderResolver 新增统一 resolveResult 入口，返回 success/value/error 与内部 warningLogged 标记；兼容旧 Optional 适配器。
- 已实现 PlaceholderApiResolver，PlaceholderAPI 2.12.3 为 compileOnly 可选依赖，不进入插件 JAR。paper-plugin.yml 使用 Paper 可选依赖结构，延迟桥接调用官方 setPlaceholders API。
- 解析限定主线程；未安装或禁用插件、异常、返回 null、结果中残留任意 `%` 均失败并记录 WARN。普通文本无需 PAPI；空字符串可为合法输出。
- ConditionEngine 已消费 Result；失败默认不通过，不能由 negate/OR 翻转为通过，已记录日志不重复 WARN，保留单次求值缓存。
- 13 个解析层 MockBukkit 测试与 39 个既有条件测试通过，完整 build 成功，总计 160 项、157 项通过、3 项既有跳过；生成 JAR 不包含 PAPI 类。
- 主类提供 getPlaceholderResolver() 供后续 ConditionContext 接入；未部署服务器 JAR，未安装 PAPI 扩展真机验证。细节及官方来源见 PLACEHOLDER_RESOLVER.md。

## 原版物品图标＋右侧文字按钮第一阶段（2026-10-11）

- 用户明确要求游戏 Dialog 内原生 Sprite 图标＋空格＋中文名称，分别配置 icon 与 label；复用 TextRenderer/DialogRenderer，不接入购买流程、Web 编辑器或自定义资源包。
- 新增 ButtonIconDefinition 和 VanillaIconResolver，显式映射 BONE/ENDER_PEARL/BLAZE_ROD/DIAMOND_SWORD 至 items 图集，STONE 至 blocks 图集。未映射材质不猜路径；无效配置 WARN 并降级保留按钮文字和回调。
- Paper 固定 Build 74，Adventure 实际 4.26.1；新增 Component.object/ObjectContents.sprite 与按钮签名核对官方 Javadoc、本地 javap并编译通过，API 参考已补充。
- 新增 /dcmenu icons 两列演示，首次原子复制 examples/icon-buttons.json，保留 /dcmenu test 与 minimal-menu.json。菜单内六个测试按钮只输出聊天确认，不执行交易。
- 新增 21 项针对性测试通过，完整 build 成功：181 项总计，178 项通过，3 项既有 MockBukkit 原生 Dialog provider 限制而跳过。
- JAR 在 plugin/build/libs/DialogCanvas-0.1.0.jar；本轮没有复制到服务器或检查原版客户端效果。配置、映射表、打开方式与现场检查清单见 TEXT.md。

## 校验结果中文说明（2026-10-14）

- 校验成功说明组件数量、是否有提醒，以及“未保存、未执行，应用需保存”；配置通过不等于生产购买功能已接入。
- 提醒标出具体按钮和字段，说明未生效的条件/动作/限购/冷却、缺失图标的降级效果和暂停设置建议；错误说明修改方式和示例，数值范围提供允许值与当前值。
- 保留 REST 错误码、字段路径和未知服务端错误；只改展示文案，不扩展生产业务。
- TypeScript 检查、56 项前端测试、42 项菜单校验器测试及隔离 Edge 28 项检查通过；Vite/Shadow JAR 构建成功，1111 个前端资源与 JAR 一致。未部署服务器。

## 用法提示浮层修复（2026-10-14）

- HelpTip 改为挂载到 body 的 fixed 顶层浮层，避免属性面板独立滚动裁切提示；窗口边缘自动避让。
- 保留键盘聚焦、鼠标悬停及辅助说明关联，支持 Escape 关闭；面板滚动关闭旧浮层，不修改业务文档。
- TypeScript、前端 50 项测试及隔离 Edge 25 项检查通过，其中覆盖跨面板显示、窗口边界和键盘/滚动行为。JAR 在项目内重新打包，未部署服务器。

## 快捷主菜单（2026-10-14）

- 用户确认潜行＋交换副手（默认 Shift＋F）以及 `/dcmenu main`；纯服务端不监听任意 Ctrl/Alt 组合键。
- MenuDefinition 根字段 shortcutEnabled，仅 true 启用；初始允许未配置，最多一个。网页属性勾选进入统一撤销历史，保存生效；其他菜单显示占用 ID。切换先取消旧菜单并保存，再设置新菜单。
- 唯一性覆盖校验、网页创建/更新、YAML 单个/批量导入、正式 JSON 启动和旧 YAML 迁移。冲突 409 SHORTCUT_MENU_CONFLICT，details 定位 /menu/shortcutEnabled；草稿和旧文件保留。主菜单 ID 与文档集合原子发布，失败沿用补偿和一致性保护。
- 原生事件在 Scheduler.sync 内处理；无主菜单、无权限、不潜行或事件已取消时保留原版交换。处理中同 UUID 不重复打开；关闭清理占用。dcmenu.shortcut 默认开放，管理子命令仍需要 dcmenu.admin。
- 自动和浏览器验证记录见 build-reports/MAIN_MENU_SHORTCUT.md；浏览器使用模拟 API，不代表真实客户端已验证。本轮仅构建项目 JAR，未部署测试服务器。

## 阶段 5.8 dnd-kit 拖拽和完整撤销

前端组件面板使用 dnd-kit 支持组件拖入正文、输入和按钮的合法区域，以及各区域内部排序。落点策略拒绝不兼容跨区域拖放；取消拖拽、无效落点和未改变位置不会产生文档修改。拖动过程中只显示 DragOverlay 和区域高亮，不持续写入历史。保留复制、删除、上移、下移按钮，键盘传感器支持列表中的方向键操作。

菜单节点使用编辑器内部稳定 ID，文档仍只保存服务端 MenuDefinition，不写入这些 ID。`nodeOperations` 每次操作从最新 Zustand 状态读取文档，连同路径映射一次性提交，避免旧数组覆盖新编辑。zundo 快照包含文档及内部 ID 映射，不包含认证、网络请求或选中状态；排序、复制、删除各自产生一个撤销点，撤销后原节点及选中项可重新定位。

全局支持 Ctrl/Cmd+Z、Ctrl/Cmd+Y、Ctrl/Cmd+Shift+Z；输入框、文本域和 contenteditable 内保留 TipTap/控件局部撤销，避免双重撤销。文本、数字属性在失焦或 Enter 时结束一次事务，拖拽在 drop 时结束一次事务。阶段 5.8 已通过 TypeScript、Vite 构建和 19 项前端测试；尚未在浏览器和原版客户端进行人工拖拽视觉验证。

## 阶段 5.10 前端、JSON/YAML 同步和单 JAR 验收

正式菜单以 `data/menus/<id>.json` 为依据，网页保存后后端在 JSON 和注册表提交完成后导出 `data/manual-menus/<id>.yml`。YAML 导出失败返回 `YAML_SYNC_FAILED` 警告但不伪装正式数据未保存；服主通过 `dcmenu reload <id>` 或批量 `dcmenu reload` 导入手动 YAML，校验失败保留旧 JSON 和注册表，批量结果允许部分成功。重启从正式 JSON 加载，未导入 YAML 不自动覆盖。

阶段 5.10 前端 TypeScript、Vitest 19 项和 Vite 构建通过。完整 Gradle 构建已尝试，但当前环境无法从 `services.gradle.org` 下载 Gradle 9.4.0 Wrapper，因此 Java 编译和最终 Shadow JAR 需在已有 Gradle 发行包或可联网环境复核。未部署测试服务器 JAR，未进行真实 Paper 客户端购买链路验证；不扩展模板、历史、diff、WebSocket 或生产购买链路。

阶段 5.10 追加实现 `dcmenu reload <id>` 与批量 `dcmenu reload`。正式 JSON 位于 `data/menus/<id>.json`，旧 `.yml` 文件保留兼容读取；手动 YAML 使用辅助目录和同步摘要基线。网页更新、删除及 YAML 导入检测未导入手动修改和双方同时修改，冲突返回明确错误并保留草稿/旧注册表。
