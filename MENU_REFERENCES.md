# 阶段 8.2：删除菜单的引用提示

更新：2026-10-15。本轮增强错误展示，不更换后端扫描器或删除事务。

## 使用

1. 删除被引用菜单时，确认删除后会看到“菜单仍被引用，暂时不能删除”。本次没有删除菜单。
2. 窗口按引用菜单 ID 分组，列出成功/拒绝动作的位置及完整字段路径。同一字段重复返回只显示一次。
3. 点击“打开此菜单”，如有未保存修改会先确认。取消保留草稿；确认后读取指定菜单，读取失败也保留原草稿。
4. 修改或移除引用者的“打开菜单”动作并保存。回到目标菜单重新删除，不会自动重试。

窗口支持 Tab/Shift+Tab、Esc 和关闭按钮；跳转请求期间按钮禁用且焦点保留在窗口内。没有强制或级联删除。无法定位的旧错误响应保留提示，不猜测引用者。

## 代码依据

| 位置 | 处理方式 |
| --- | --- |
| `MenuRepository.java:22`、`:263` | 单工作线程协调器内检查同步状态、revision、外部引用并删除；没有把检查移到 HTTP 或客户端 |
| `MenuRepository.java:270` | 跳过目标自身，用现有不可变引用集合筛选，再通过 referencePaths 返回精确位置 |
| `MenuValidator.java:184`、`:247`、`:289` | 实际按钮的两个动作分支编译 open_menu；只有真实动作才加入引用集合 |
| `MenuValidator.java:317`、`:326` | notice/confirmation/multi_action 各按钮位置及 actions/denyActions 的精确字段路径；不遍历玩家数据 |
| `MenuReferencesDialog.tsx:8` | 只解释已有服务端路径并分组去重；中文位置复用 validationLocation |
| `MenuReferencesDialog.tsx:46` | 跳转忙碌、失败提示、焦点保持；禁用聚焦按钮之前将焦点移入面板，修复浏览器验收发现的 Esc 失效 |
| `App.tsx:143`、`:211`、`:277` | 复用未保存保护和读取逻辑；仅 409 MENU_REFERENCED 打开弹窗，用 documentEpoch/认证状态淘汰旧结果 |

上述后端生产代码本轮未修改。当前 ActionStepDefinition 不含嵌套动作容器；未知嵌套 actions 字段被拒绝，不能宣称支持任意层级动作引用。将来加入可执行容器时，须在现有编译与 referencePaths 中一并扩展和回归，不能递归扫描任意 JSON。UI 跳转只打开引用菜单，不自动选中业务动作或替服主修改配置。

## 验证覆盖

| 范围 | 证据 |
| --- | --- |
| 各按钮成功/拒绝分支 | MenuDeletionReferencesTest 10 项参数化测试，检查 409、精确路径及文件/注册表不变 |
| 多个引用者、多个字段、自身引用 | 同测试类；外部引用移除后允许删除自身引用菜单，JSON/YAML/基线一起移除 |
| 普通字符串及菜单形状玩家数据误判 | 同测试类；不计引用，允许删除目标且保留来源数据 |
| 未支持的嵌套动作 | 同测试类；422 INVALID_FIELD，不假装执行或递归计引用 |
| revision 与 YAML 未导入冲突 | 同测试类；原错误码不变，文件保留 |
| 检查与删除之间的集合竞争 | 同测试类；文件删除门闩故障点，后排引用创建不得发布悬空引用；引用创建先入队则删除拒绝 |
| HTTP 契约 | 既有 MenuHttpTest 真实回环 HTTP 验证引用错误、认证、CSRF、revision 与 CRUD；不修改错误 details 契约 |
| UI 分组去重/旧 details | MenuReferencesDialog.test.ts 3 项；路径数字排序、中文位置及原路径保留 |
| 真实浏览器交互 | verify-menu-references.mjs：生产前端 dist + 独立 Edge + 模拟 REST，9 项通过；包括草稿取消/确认、读取失败、焦点、旧响应、冲突及 204 |

浏览器截图 `build-reports/menu-references-browser.png` 已检查弹窗层级、分组、换行及路径显示。模拟 REST 专门注入重复/缺失 details、503、迟到请求；不代表实际 Paper 删除验收。后端使用真实临时文件及原协调器，并有既有回环 HTTP 测试；未访问原服务器或游戏客户端，没有部署其 JAR 或修改数据。外部编辑器与进程的并发边界仍遵守现有文件同步约束，不新增跨进程锁。

最终结果：TypeScript、Vite 及完整 Gradle build 成功；前端 156/156 通过，Java 513 项中 509 通过、4 项既有跳过，0 失败/错误。跳过为三个 Dialog 原生 Provider 场景及一个完整物品属性场景，不计通过。浏览器 9/9 检查通过且无脚本异常。1050 个前端资源与单 JAR 逐字节匹配；新包导出 `dist/DialogCanvas-0.1.0.jar`。Vite 既有大块体积提示和 ByteBuddy 既有 Unsafe 提示仍在，未扩展无关优化。

## 复现

在 frontend 执行：

```powershell
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=../build-reports/menu-references-frontend-results.json
node node_modules/vite/bin/vite.js build
```

在项目根目录执行（Java/Gradle 缓存目录按本机调整）：

```powershell
$env:JAVA_HOME='E:\project\java25'
$env:GRADLE_USER_HOME='C:\Users\小米周\AppData\Local\Temp\DCMenu-verification\gradle-home'
./gradlew.bat :plugin:test --tests dialogcanvas.web.menu.MenuDeletionReferencesTest --tests dialogcanvas.web.menu.MenuHttpTest --tests dialogcanvas.web.menu.MenuRepositoryTest --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
node scripts/verify-menu-references.mjs
./gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
python scripts/verify-menu-references-artifacts.py
```

浏览器脚本要求本机已有 Edge，仅启停自己创建的独立实例。汇总验证/导出脚本只操作工作区构建产物。最终计数、跳过项和 JAR 摘要见 `build-reports/menu-references-results.json`；既有跳过项不计通过。
