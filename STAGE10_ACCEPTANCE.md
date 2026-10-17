# 阶段 10.5 最终构建与交付验收

[项目入口](README.md) · [中文 Wiki](docs/wiki/README.md) · [开发文档](DEVELOPER.md)

版本 **DialogCanvas 0.1.0 / DCMenu**，日期 2026-10-16。最终同一 JAR 11,432,304 字节；SHA-256：

`8b2357f1bff068aa97089ddd988bae8c319a0165fb5ac3b757d42f23c5b00423`

## 结论和兼容范围

完成必要自动回归、四个官方 Paper 版本的隔离服务端和实际浏览器验收，未发现阻塞本次交付的确认问题。**原版客户端与真实玩家交易尚未验收**，不宣称全部场景通过。没有部署或访问原服务器、配置、菜单及玩家数据。

| Paper 版本 | 构建 | 官方渠道 | 隔离服务端 | 独立 Edge + 真实 REST |
| --- | --- | --- | --- | --- |
| 26.1.1 | 29 | ALPHA | 15/15 | 5/5 |
| 26.1.2 | 74 | STABLE | 15/15 | 5/5 |
| 26.2 | 134 | STABLE | 15/15 | 5/5 |
| 26.3 | 194 | STABLE | 15/15 | 5/5 |

四次使用相同 SHA-256 的生产 JAR，无按版本替换代码；编译依赖仍为 `26.1.2.build.74-stable`，最低 API 声明保持 `26.1`。26.1.1 官方最新构建为 **Alpha**，不把它写成稳定版。官方当前没有单独名为 `26.1` 的下载：26.1 系列按 26.1.1/26.1.2 实测，不能声称测试了不存在的安装包。26.2/26.3 为正式版本渠道。不承诺每个历史构建、RC/快照、未来更新、其他服务端分支或混合模组端。

版本/下载哈希来源：[Paper 官方下载 API](https://fill.papermc.io/v3/projects/paper)；API 参考：[26.1](https://jd.papermc.io/paper/26.1/index.html)、[26.3](https://jd.papermc.io/paper/26.3/index.html)。下载均核对官方 SHA-256；记录 `build-reports/paper-compatibility-runtimes.json`。不是仅修改 yml 就宣称兼容。

## 构建和自动验证

| 项目 | 结果 | 证据/边界 |
| --- | --- | --- |
| TypeScript | PASS | `pnpm run typecheck` |
| 前端 Vitest | PASS：176/176，24 文件 | `build-reports/stage10-5-frontend-results.json`；直接运行锁定依赖的 Vitest CLI |
| Vite 生产构建 | PASS | JS/CSS/index 和本地纹理；现有主 JS 大于 500 kB 的体积警告保留，不是构建失败 |
| 完整 Gradle build | PASS | 616 项：612 通过、4 跳过、0 失败、0 错误；`plugin/build/reports/tests/test/index.html` |
| 单 JAR 资源一致 | PASS | web/ 全部 1050 个文件与当前 frontend/dist 逐字节一致，反馈目录与前端共用文案一致；不是只检查文件名 |
| 教学示例 | PASS | WikiExamplesTest 3 项；10 份 YAML 全集引用及 JSON/YAML 往返；四版实际注册表下分别解析、校验并保存全部 10 份 |
| 权限/身份 | PASS（自动层） | ManagementCommandTest、TransactionalActionEngineTest；player/op/console、临时 OP finally、无命令允许列表；实际玩家及第三方命令未执行 |
| 会话边界/改密 | PASS（自动层） | AuthServiceTest/AuthHttpTest：受控时钟覆盖 24h 过期与改密撤销；不是实等 24h；四版真实退出/重启另测 |

Gradle 已把 `examples/wiki` 登记为 test 输入，示例修改会使测试缓存失效。生产功能及依赖未改，最终 JAR 哈希与隔离测试一致。构建保留 ByteBuddy/Unsafe 和 Vite 体积警告，不声称构建无警告。

### 明确跳过的四项

- `dialogcanvas.dialog.DialogRendererTest.[1] NOTICE`
- `dialogcanvas.dialog.DialogRendererTest.[2] CONFIRMATION`
- `dialogcanvas.dialog.DialogRendererTest.[3] MULTI_ACTION`
- `dialogcanvas.engine.action.ActionItemFactoryTest.fullCustomSwordHasRealEnchantsMetadataAndAdditionalAttributes()`

前三项由 MockBukkit 缺少动态 Dialog provider 导致；第四项为其未实现的真实物品属性行为。均**不算通过**。隔离 Paper 的 fixture-only 探针已构造 10 份教学菜单的真实原生 Dialog（含三类型、四输入、正文和 Sprite），补充服务端链接证据，但没有客户端 showDialog 显示或真实发货证据。探针仅在工作区，不打入交付 JAR。

## 同一最终 JAR 的实际隔离流程

每版 `scripts/verify-stage10-final-paper.py --version <版本>` 启动全新工作区实例及独立 Edge；四版共 **60 项服务端检查、20 项浏览器检查**。每版两个完整启停周期；无玩家 Bot、不执行教学商店、OP/控制台商品命令或真实交易。

1. 首次随机密码只捕获在内存、先保存哈希、重启不再输出；控制台 `dcmenu editor` 为实际地址；系统套接字确认仅 127.0.0.1。
2. JAR 内 index、JS、CSS、PNG 与真实 HTTP 响应一致。独立 Edge 登录、新建、标题/正文编辑、校验、保存、重新读取、下载草稿 YAML、导入预览及取消；截图已查看。页面发起外部 HTTP 来源为 0、未处理脚本异常为 0。
3. 未认证 API/错误密码、Cookie、本机 Origin/CSRF；同 revision 两次并发 PUT 恰好一次 200、一次 409；非法配置 422 不改正式文件。
4. success/denyActions 两分支 open_menu 引用阻止删除；移除引用菜单后正常删除。YAML 手改后保存/删除均 409 且保留文件；控制台 reload 导入，再读取 JSON/注册表一致、状态 synced。
5. 10 个交付教学文档经实际生产解析/校验/保存；console open 缺在线目标明确拒绝；退出 204 后旧会话 401。
6. 停服排空并释放端口。带未导入 YAML 修改重启：原菜单/revision/哈希保持、YAML 原字节保留且 pendingImport、旧会话 401，重新登录 200；第二次关闭同样释放端口。

Node.js 只由验收工具启动浏览器，工具退出后 Java 服务继续完成 REST、文件操作与重启；Paper 插件运行不依赖 Node.js、Vite、独立网站或外网 CDN。首次准备服务端/下载依赖是测试环境安装步骤，不是编辑器运行依赖。

证据：`build-reports/stage10-5-<版本>-paper-results.json`、`-browser-results.json`、`-browser.png`；详见交付证据目录。脚本仅接受固定版本，不接受生产服务器路径。初始化密码、会话、Cookie、CSRF 不写入交付报告。

### 测试过程中的失败及处置

- Windows 下 26.3 的控制台中文输出与脚本 UTF-8 不一致，首轮中文断言超时；给隔离 JVM 显式 stdout/stderr UTF-8 后完整重跑通过，不改生产编码逻辑。
- 26.1.2 首轮浏览器在字段提交渲染前开始下一次编辑，随后一次因自动点击/焦点时序未到达校验；保留诊断并校正真实点击、失焦及等待顺序，最终完整重跑结果为准。未把失败运行计为通过。
- Windows pnpm exec 的 Vitest 可执行文件查找失败，改用 `node node_modules/vitest/vitest.mjs run`；176 项真实执行通过。
- 隔离 26.2/26.3 的 OSHI 报本机 Windows Perflib 计数器问题，服务仍正常；不修改系统计数器/原服，不宣称整份 Paper 日志无 ERROR。

## 交付清单与旧产物

- `dist/DCMenu-0.1.0-Stage10.5-Paper26.1-26.3/`：单插件 JAR、使用文档/Wiki、十份中文注释 YAML、必要源码参考、脱敏验收证据及逐文件校验清单。
- `dist/DCMenu-0.1.0-Stage10.5-Paper26.1-26.3.zip`：上述完整交付包。只将其中 **DialogCanvas-0.1.0.jar** 安装到 plugins，不安装测试探针。
- `dist/DCMenu-WikiExamples-0.1.0-Stage10.5.zip`：只有当前十份教学 YAML 与安装说明，不含旧 JAR。
- `dist/SHA256SUMS-Stage10.5.txt`、`dist/DELIVERY-Stage10.5.json`：JAR/ZIP 哈希、大小、版本矩阵和验证汇总。校验 ZIP 的哈希保存在 ZIP 外，避免自引用。
- `dist/DialogCanvas-0.1.0.jar` 同步为最终同一份 JAR；历史 ZIP 保留不覆盖，均在 `dist/README.md` 的“历史产物”中明确标记 **不是本次交付**，含旧 JAR 的包尤其注明。

安装前正常 stop 并等 Java 退出，备份原数据后再替换；只留一个插件 JAR。正常启动、运行 `dcmenu editor`、在服务器电脑登录。示例先 01→10 导入，只在测试账号执行有影响的动作。原教学文件注释不会在导出时保留。详见 [Wiki 安装](docs/wiki/01-start/installation.md) 和 [示例说明](examples/wiki/README.md)。

## 待人工验收 / 未执行

| 项目 | 状态与原因 |
| --- | --- |
| 原版客户端逐版显示 | 未执行：三类菜单、文字/渐变、Sprite、tooltip、输入、布局和附魔闪光需客户端检查 |
| 真实 Vault/PAPI | 未执行：当前最终四版隔离实例未装真实经济/变量扩展；不把模拟经济或元数据配置验证作为真实结果 |
| 第三方命令/权限插件 | 未执行：在线账号 player/op/console 与目标插件权限需实际验证 |
| 玩家快捷键/交易 | 未执行：Shift+F、背包/自定义武器/发货、买卖、限购冷却、补偿需测试账号；不重放不确定交易 |
| 硬断电/所有历史构建 | 未执行且不承诺：正常重启通过不等于断电零丢失，官方 Alpha 不等于稳定质量 |
| 原服务器部署 | 未执行：无部署授权，不改原服 JAR/配置/菜单/玩家数据 |

自动层或隔离服务通过不能替代这些项目；当前没有确认未修复的交付阻塞问题。
