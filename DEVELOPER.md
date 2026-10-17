# DCMenu 开发文档

[README](README.md) · [中文 Wiki](docs/wiki/README.md) · [当前文档验收](STAGE10_DOCUMENTATION.md)

本文件按当前实现核对（2026-10-16），不是扩展路线图。生产 JAR 当前为1.0.0，已增加首次生成十份带注释教学文件；编译 Paper API 固定 `26.1.2.build.74-stable`，四版基线验收见 [最终验收](STAGE10_ACCEPTANCE.md)，最低 `api-version` 保持 26.1；菜单模型版本1。最新示例生成验证见 [内置示例说明](BUNDLED_EXAMPLES.md)。

最新物品输入及资源打包规则见 [注册名说明](REGISTRY_ITEMS.md)：不嵌入物品 PNG，网页文字占位，游戏端沿用 ItemStack/Sprite。

## 项目结构与固定依赖

| 路径 | 职责 |
| --- | --- |
| plugin/src/main/java/dialogcanvas | 主类、命令、Scheduler、Dialog、逻辑、存储、Web/认证/菜单、归档/审计 |
| plugin/src/main/resources | paper-plugin.yml/config.yml、图标映射及原资源；前端生成物不直接清空此处 |
| frontend/src | React界面、类型、Zustand/zundo、预览/属性/文字/逻辑、API客户端 |
| frontend/public、frontend/dist | 本地静态素材及独立生产构建输出 |
| plugin/build/generated-resources/frontend/web | Gradle同步的前端构建资源，打入JAR的web/ |
| plugin/src/test | JUnit/MockBukkit及真实Javalin HTTP验证 |
| scripts、build-reports | 验证工具和有日期/范围的证据，不是运行服务依赖 |
| examples/wiki、docs/wiki | 本轮教学与中文使用入口；生成源 scripts/build-wiki.py/wiki-content.py/wiki-reference.py |

Gradle9.4.0 Kotlin DSL，Java25 toolchain/release；Shadow9.6.1。Javalin7.2.2、Jackson3.2.3（tools.jackson）、SnakeYAML2.2、Favre BCrypt0.10.2、Jetty12.1.14。Paper提供SLF4J，不打入额外日志实现。PAPI2.12.3 compileOnly；Vault通过生产适配发现，不把经济插件打包。

前端锁定 pnpm 11.19.0，本轮环境为 Node.js 24.19.0；React 18.3.1、TS 5.7.2、Vite 5.4.11、Tailwind 3.4.16、dnd-kit 6.3.1、Zustand 5.0.2、zundo 2.3.0、TipTap 2.11.5（@tiptap/pm 2.27.3）、react-colorful 5.6.1。完整版本看 package.json 与 pnpm-lock.yaml。测试使用 JUnit 6.1.3、MockBukkit 4.115.0、Vitest 2.1.8；不在文档流程升级依赖。

## 构建与开发

```text
cd frontend
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm test
pnpm run build
cd ..
./gradlew build
```

Windows最后一步用 `./gradlew.bat build`；JAVA_HOME指JDK25。Wrapper首次需下载发行包，离线需提前缓存依赖。Gradle build会Vite构建、Sync到生成资源、Shadow单JAR，输出 `plugin/build/libs/DialogCanvas-1.0.0-Paper-26.1-26.3.jar`；**不会自动跑TS检查/Vitest**，这两项需单独执行。运行不依赖Node/Vite或CDN。

开发 `pnpm dev` 固定127.0.0.1:5173、strictPort、cors=false；同源守卫先检查原始Host/Origin，再代理/api至127.0.0.1:8080并改Origin，后端继续Cookie/CSRF验证。后端端口改变需同步开发配置，不能改成任意来源CORS。开发者自己的服务器也只能停服后替换JAR。

## 模型、文本与存储

`BundledExamples.install` 在既有初始化工作线程执行：将 JAR 内 `examples/menus/` 的十份教学 YAML 与中文使用说明原样写至插件 examples/，使用 AtomicFiles 缺失检查与原子写入，仅补齐缺失项，不覆盖手改文件。Gradle 从 examples/wiki/menus 同步资源，避免第二份配置模型/解析器；普通仓库不会自动扫描教学目录，按需使用已有浏览器/控制台导入。生成失败记录固定诊断码，不影响其他初始化，也不触碰正式 JSON、手动 YAML、基线或玩家数据。

[MenuDefinition](plugin/src/main/java/dialogcanvas/model/MenuDefinition.java) 及派生POJO为唯一格式。ModelPojo字段可见、getter/setter不可见，NON_NULL；统一 MenuJson.createMapper 严格未知字段、重复键、尾随token、标量类型与深度。TextSpec字符串简写是legacy；对象format/value支持plain/legacy/minimessage/component，内部shorthand不持久化。网页只转换已验证子集；未知复杂原文保护。文本细节看 [字段表](docs/wiki/06-reference/menu-fields.md)。

MenuYaml为现有安全SnakeYAML入口，严格UTF-8、1MiB、重复键/类型/深度/集合别名保护。MenuValidator.prepare结合捕获后的不可变物品/声音/附魔/属性集合做模型、业务及引用校验。meta的反射Schema不是全部语义；根版本1，仅渲染三类菜单。网页不保存HTML、TipTap JSON、选中项或内部节点ID。

MenuRepository在集合串行工作线程内准备快照/引用/唯一主菜单；更新检查revision和YAML状态。正常写：校验→原子JSON→注册表发布→YAML/基线；发布失败补偿，失败不明则停用保护。JSON/注册表已提交后YAML失败返回成功响应加YAML_SYNC_FAILED，不诱导重提。删除也在协调器内查引用/revision/同步，再删除/发布，失败沿用保守补偿。AtomicFiles使用目标同目录临时文件、完整写和原子替换，不静默降级普通覆盖；多个文件不是原子事务。详见 [持久化审计](PERSISTENCE_AUDIT.md)。

## REST、认证及并发

精确契约：[WEB_API.md](WEB_API.md)。认证login/logout/session/password，菜单list/get/create/update/delete/validate/meta，加两条只读浏览器YAML转换。除精确POST login外所有/api认证；非安全方法校验同源Origin及CSRF。随机会话在内存、24小时、HttpOnly/SameSite=Strict、Path=/api，本机HTTP不设Secure；密码BCrypt与会话不写URL/前端持久存储。改密撤销全部会话，退出当前，重启全部失效。

revision是内容关联的不透明字符串，客户端不可推导文件实现；同名YAML导入预览捕获baseline/revision，确认只进入草稿，最终正常PUT检查。导出当前草稿不提交。401保留内存草稿，超时写入结果不确定不盲重试；documentEpoch/请求序号阻止旧响应覆盖新文档。同步状态与错误/警告按现有契约，不创造“覆盖服务器文件路径”接口。

## 主线程与异步生命周期

Scheduler.sync/syncAfter是异步入口回主线程的统一方式。玩家、权限、背包、命令、Dialog、Vault、PAPI与Bukkit注册表访问有明确主线程保证；已有主线程调用不重复包裹。纯模型/不可变环境快照/线程安全记录可后台。

Future的thenApply/thenCompose/whenComplete不保证主线程；每次访问游戏状态前单独追踪调用线程。文件I/O、BCrypt和正常运行阻塞等待均不在Minecraft线程。插件关闭唯一已批准例外：Scheduler拒绝新任务后，onDisable最多30秒有界等后台清理，避免Paper立即关类加载器导致Jetty收尾失败；实际I/O/HTTP关闭仍后台。离线、停止、拒绝调度及取消必须清理gate/预留和传播失败，不能提前成功。详见 [线程审计](THREAD_SAFETY_AUDIT.md)。

## 条件、动作与恢复

ConditionEngine结构编译与动态求值分开；五类叶节点，AND/OR短路、negate；FAIL≠ERROR，已访问错误不得通过取反/OR隐藏，WARN去重且脱敏。PAPI缺失/异常/未替换/非法数值不是默认0。message解析是提交后路径，不机械套用条件拒绝策略。

ProductionActionEngine同玩家准入，ClickGuard同按钮去重；LimitService UUID队列预留/结算，ProductionStore持久意图/业务数据/最终收据。TransactionalActionEngine可逆动作与提交后副作用分阶段，失败逆序补偿；延迟/chance/return按已确认语义。必要额度/冷却落盘先于副作用，effectsPending不等于completed；不确定状态保留阻断。临时OP finally恢复；不重新引入命令允许列表。真实外部服务不能承诺跨插件原子事务。详见 [生产链路](PRODUCTION_PURCHASE.md)、[占位符审计](PLACEHOLDER_FAILURE_AUDIT.md)。

## 实际存在的公共类和内部SPI

这些是当前Java public入口，**不是版本化的第三方SDK承诺**；JAR重定位还会改变Jackson等依赖类型名。

| 入口 | 当前用途与边界 |
| --- | --- |
| DialogEditorPlugin.getDialogRenderer() | 运行后取渲染器；show(Player,MenuDefinition)回主线程并返回Future，render要求调用方在主线程 |
| getMenuRepository() | 运行期仓库，definition异步取脱离快照；内部CRUD/转换并非绕过认证给网页公开新入口 |
| getPlaceholderResolver() | 现有resolveResult及结构化Result；生产调用主线程；不是PAPI expansion注册器 |
| StorageProvider / MenuPersistence | 内部存储抽象/字节与辅助文件接口；FileStorage生产实现，未注册Bukkit可替换服务 |
| ActionEngine / ExecutableAction | 点击入口、execute/rollback抽象；动作类型由当前封闭模型/分发逻辑决定，不支持自动发现任意动作插件 |
| ActionServices.Economy/PlayerData/Menus/RecoveryStore/Conditions/Change | 构造注入的内部适配/测试接口，Change包含rollback与恢复资料；不是服主在配置中指定的扩展类 |
| PlaceholderResolver / PlayerDataView | 条件消费的注入接口；不是任意脚本求值器 |

没有对外自定义 Bukkit 菜单创建/保存/交易事件，没有注册动作/物品提供者的公共注册表、独立SDK制品或稳定第三方兼容承诺。不要虚构事件监听和服务注册调用；未来扩展需先设计契约，不能只加文档。

## 测试、隔离验收与文档复现

`pnpm run typecheck/test/build`；`./gradlew.bat :plugin:test`，报告plugin/build/reports/tests/test。文档示例专用 `:plugin:test --tests dialogcanvas.web.menu.WikiExamplesTest --rerun-tasks`，使用生产解析/校验/仓库，不执行真实商品动作；文档生成 `python scripts/build-wiki.py`，静态校验 `python scripts/verify-wiki-docs.py`。

`python scripts/verify-wiki-paper.py` 只从工作区既有Build74夹具创建全新实例，在独立端口验证安装、首次密码、REST/YAML重载和重启；独立Edge脚本连接该夹具验证真实浏览器操作。前提是本机已有JDK25、缓存Paper及Edge；脚本环境变量/参数见脚本头与本轮报告，不能盲指向生产目录。

MockBukkit用于线程/交易/故障注入；无头浏览器只验证网页，不是Minecraft Bot。隔离Paper不等于原版客户端。真实Vault/PAPI动态值、客户端显示、跨插件命令行为和硬断电保证必须单独标注。历史脚本有的会读取其他服务端安装，请先读脚本；本轮新脚本只访问工作区，禁止以原服务器数据做文档验证。
