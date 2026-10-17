# DCMenu 阶段 5.10 验收

日期：2026-10-13。范围：现有前端编辑器、JSON/YAML 同步、认证/REST 和单 JAR。没有新增模板、历史、diff、WebSocket、复杂模型预览或生产购买链路。

## 结果

| 验证层 | 结果 | 证据 |
| --- | --- | --- |
| TypeScript | `tsc --noEmit` 通过 | 最终源代码类型检查 |
| 前端针对性测试 | 27 项全部通过 | Vitest：API 8、状态 7、节点/拖拽 6、预览 3、文本 3 |
| 前端生产构建 | 通过 | Gradle frontendBuild 的 Vite 输出，JS 约 575 kB；有体积提示，无构建错误 |
| Gradle 完整构建 | 通过 | `build-reports/stage5-build.log` |
| 后端测试 | 总计 256，253 通过、3 跳过、零失败 | `plugin/build/reports/tests/test/index.html` |
| 单 JAR 资源比对 | 首页、JS、CSS 与 dist 逐字节相同 | `build-reports/stage5-jar-results.json` |
| 实际隔离 Paper + Edge | 20 项 PASS | `build-reports/stage5-actual-results.json` |

3 项跳过均为既有 DialogRendererTest 的 NOTICE/CONFIRMATION/MULTI_ACTION 原生 Dialog provider 限制，未记作通过。

产物：`plugin/build/libs/DialogCanvas-0.1.0.jar`，10,720,382 字节。

SHA-256：`c835046333497812b0db82000d0a5e9aedf71e51eb2335d1a34b1756c2e0594e`。

实际 Paper 使用的 JAR 与该产物摘要相同。用户服务器 `E:/project/server26.1.2` 未更新或重启。

## 按要求区分验证范围

| 项目 | 自动验证 | 实际服务器/浏览器验证 |
| --- | --- | --- |
| 控制台地址、页面、登录 | 回环 HTTP、静态资源、端口占用测试 | Paper 控制台输出 127.0.0.1:18081；打包页面正常，真实登录表单成功 |
| 新建、组件、文字、图标、逻辑 | 文档往返、文本子集、图标映射与模型校验 | 浏览器新建 multi_action，添加正文/按钮、TipTap 文字、钻石剑独立图标、AND 权限条件、DAILY 限购 |
| 拖拽、撤销重做、校验、保存、重读 | 稳定 ID、复制/删除/排序、拒绝跨区、取消、历史恢复 | 鼠标实际排序，撤销重做后保存顺序正确；键盘拖拽 Escape 取消；校验、创建、重读保留内容 |
| 网页生成 JSON 和 YAML | 同模型 JSON/YAML 往返、revision/快照一致 | 真实网页保存生成正式 JSON 与插件目录手动 YAML；正式文件等于 API 文档 |
| 手动 YAML 导入 | 无效导入不发布，逐项批量结果、引用检查 | 修改 YAML 标题，真实控制台 reload，API 与重启后网页读取新标题 |
| 未导入 YAML、revision、导出失败 | 双方修改/缺失基线拒绝、导出故障注入、恢复与删除补偿 | 未导入 YAML 使 PUT 返回 409，JSON/YAML 原字节保留；模拟第二标签页更新后 UI 旧 revision 返回 409 |
| 过期、字段、不可用、旧请求 | 24 小时边界时钟测试；401/409/422/503、details；断网/异常响应；旧保存响应及新编辑保护 | 重启后旧浏览器 Cookie 401；UI 重新登录、重新读取和退出 401；未实等 24 小时或断网交互测试 |
| 重启加载和同步 | 导出意图恢复、基线缺失/损坏保护、迁移备份/同名冲突 | 正常 stop/start 后 JSON/revision 保留，待导入 YAML 不改写，网页显示 pendingImport |
| 单 JAR 与运行资源 | 资源字节匹配、无 node_modules 资源 | 页面由实际 Paper/Javalin 提供，所有观察到的页面 HTTP 请求同源，无 Vite 服务或外网 CDN |
| 监听与关闭 | 停止清理测试 | Windows TCP 表仅 127.0.0.1；正常关闭后端口能重新绑定；测试结束无 18081/9223 监听 |

实际浏览器为独立配置目录的 Edge 无头实例，通过 CDP 驱动，没有使用游戏 Bot。Node.js 只运行构建/浏览器测试驱动，服务器页面由 JAR 提供。

浏览器证据：`build-reports/stage5-browser-results.json`、`stage5-browser-restart.json`、`stage5-editor.png`；服务器日志：`stage5-paper.log`；汇总：`stage5-actual-results.json`。

## 验收中修复的问题

- 正式存储改为只读写 JSON，独立 YAML 入口恢复到插件目录 menus；移除误用旧 YAML 的兼容写入。
- 导出使用正式提交前捕获的 YAML 字节，持久化意图后再次检查，避免覆盖提交期间出现的手动修改；恢复只接受摘要明确匹配的状态。
- 删除对 JSON、YAML、基线和内存执行失败补偿；残留同步基线不被当作新菜单自动导入。
- 前端补齐重新读取/删除入口；保存使用最新正式 metadata，防止后续保存错误；文档代次阻止旧响应污染已切换菜单；保存后撤销与草稿判断独立于同步警告。
- 条件 operator/comparison/ValueSource 按后端契约生成；TIMED 切换、数据增量 key、JSON 数据值、文字源码和选区渐变修复。
- 首页升级出现空白页：可复现 JAR 固定时间戳和同长度 index 使旧静态 ETag 误匹配。现在根首页及 index.html 显式返回当前 HTML，no-store，条件请求返回 200；复用旧浏览器缓存实际通过。
- 读取菜单显示服务器 YAML 同步状态；校验错误 JSON Pointer 正规化并显示节点路径。

## 复现

### 构建和自动测试

```powershell
cd E:\project\menu\frontend
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run
node node_modules/vite/bin/vite.js build
cd ..
$env:JAVA_HOME='E:\project\java25'
$env:GRADLE_USER_HOME='C:\Users\小米周\AppData\Local\Temp\DCMenu-verification\gradle-home'
./gradlew.bat build --offline --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Dfile.encoding=GBK'
```

上述缓存路径是本次机器环境。其他电脑使用自己的 Java 25/Gradle 缓存，首次准备依赖见 FRONTEND.md。

### 隔离实例与浏览器

```powershell
cd E:\project\menu
python scripts/verify-stage5.py
```

依赖现有 `build/stage4-paper` 的 Paper 26.1.2 Build 74 发行包、libraries/cache/versions 及专用测试配置，Java 25 和本机 Edge。脚本复制到 `build/stage5-paper`，使用 Web 18081、Minecraft 25576、CDP 9223，并仅替换隔离目录 JAR。若这些端口已占用先关闭相应测试进程；不要指向用户服务器。脚本不会把密码/Cookie/CSRF 写入结果报告。

重复运行会恢复和删除脚本自己创建的 stage5_accept 测试文件；不要在该隔离目录保存需要保留的手动工作。源码中的固定密码仅适配该专用 BCrypt 测试配置，不用于用户服务器。

### 在用户服务器人工复核

授权部署后，控制台 `dcmenu editor` → 本机浏览器登录 → 新建菜单 → 添加文本与按钮 → 修改文字/图标/条件 → 排序与撤销重做 → 校验 → 保存 → 重新读取。检查 `data/menus/<id>.json` 和 `menus/<id>.yml`。

修改手动 YAML 的标题，先网页保存确认显示冲突且草稿保留，再执行 `dcmenu reload <id>`，网页重新读取。保持同一浏览器正常 stop/start，旧 session 应 401，重新登录后读取 JSON；待导入 YAML 应保留。具体文件与恢复规则见 YAML_EDITING.md，HTTP 状态和错误示例见 WEB_API.md。

## 未实际验证或不在范围

- 未部署原服务器，未操作用户日常浏览器或原版游戏客户端。
- 未逐项手动点击所有正文/输入/条件/动作组合；自动测试与服务端校验覆盖相应结构。浏览器中实际配置的是权限条件和 DAILY 限购，没有测试扣款、发货或命令执行。
- 未实等 24 小时、未对真实生产文件注入写权限故障、未真实断电或强杀进程。时钟过期、故障补偿和中断导出恢复由自动测试验证；多文件整体原子/断电持久性不作保证。
- 浏览器复制、跨区拒绝、拖入新增的边界主要由针对性自动测试验证；实际浏览器确认了列表鼠标排序和键盘取消。
- 网页图标目前为映射占位，没有原版缩略图素材；画布是近似结构预览，不承诺游戏像素一致。复杂 MiniMessage/Component 保留源码，未扩大复杂预览范围。
- 生产购买链路、模板、历史、diff、WebSocket、游戏预览接口均未扩展。
