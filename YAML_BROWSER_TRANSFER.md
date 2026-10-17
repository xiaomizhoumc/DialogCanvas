# 浏览器 YAML 导入导出

## 使用步骤

1. 使用 `dcmenu editor` 显示的本机地址并登录。
2. 点击顶部“导入 YAML”，选择本机 `.yml/.yaml`。文件须为 UTF-8、最多 1 MiB、`schemaVersion: 1` 的 DCMenu 菜单根文档；不能使用 API 响应包装或其他插件格式。
3. 核对 ID、标题、校验结果、警告及同名信息。文件名不决定 ID。无效配置显示字段错误，禁止确认；取消保留当前草稿。
4. 点击“确认进入草稿”。有未保存修改时需明确确认替换；旧文档撤销历史被清空。此时服务器未改变。
5. 检查并点击“保存”：新 ID 正常新建；同名使用预览时版本正常更新。revision 冲突保留草稿，需核查后重新读取/导入；服务器 YAML 有未导入修改时先按既有流程处理，不删除同步基线来强制覆盖。
6. “导出草稿 YAML”下载当前草稿。合法字段会先失焦提交并校验；非法数字、未应用颜色/渐变需先处理。不写正式 JSON、服务器 YAML 或同步状态。需要已保存内容时先保护草稿并重新读取。

正式保存仍为 JSON → 注册表 → YAML/基线；部分成功仍明确提示“正式数据已保存，但 YAML 同步失败”，不要重复提交。metadata 由服务器维护，导入文件不能覆盖正式时间/版本。选择文件仅传内容，不指定服务器路径。需要变为另一个新菜单时修改 YAML 内的合法 ID 后重新导入。

## 格式及注释

不引入新菜单格式；复用 MenuDefinition、统一 Jackson、MenuYaml、MenuValidator 和 MenuRepository。严格拒绝未知字段、不安全 YAML、非法编码/引用。旧目录迁移不是跨插件格式转换，不宣称兼容 KaMenu。

注释不会进入模型；导出为两空格块式 YAML，不保留手写注释、锚点、引号风格和原排版。导出文件是菜单根文档，不含响应 envelope 或 revision；允许内容语义往返，不承诺字节一致。正文、TextSpec 简写、缺省和 null 不因预览而自动标准化。

## 验证与边界（2026-10-16）

- Java 完整构建：613 项中 609 通过、4 项既有 MockBukkit 跳过，0 失败/错误。新增 10 项真实 Javalin HTTP 用例，覆盖有效根文档往返、显式 null/简写、未知字段、重复键、非法标签/集合别名/编码、超限、Content-Type、认证/Origin/CSRF、同名 revision 和已有 YAML 待导入保护、缺失引用；转换未写文件或注册表。
- 前端：176 项测试通过，包含 6 项新增文件大小/编码、请求契约、草稿/基线/revision/内部 ID/撤销验证；TypeScript、Vite 及完整 Gradle 构建通过。
- 独立 Edge 使用生产 dist 和模拟 REST 检查文件选择、ID/标题预览、取消、同名冲突、新建、在编辑字段失焦导出、下载重导入、错误和超限、取消后的旧响应；实际解析安全及文件一致性由真实 Javalin HTTP 测试覆盖。证据见 `build-reports/yaml-transfer-browser-results.json` 与 `yaml-transfer-frontend-results.json`。
- 最终浏览器 11 项通过，截图已查看。1050 个前端文件及共享错误文案与 JAR 逐字节匹配；汇总 `build-reports/yaml-transfer-results.json`。工作区 `dist/DialogCanvas-0.1.0.jar` 为本阶段构建，11,432,304 字节，SHA-256：`8b2357f1bff068aa97089ddd988bae8c319a0165fb5ac3b757d42f23c5b00423`。
- 本轮没有部署原服务器、读取/修改原服务器菜单或玩家数据，也没有原版客户端或新增隔离 Paper 验证。浏览器模拟 API 不计作原服务器验收。需服主部署后复核本机实际会话下的导入、保存、重载和游戏内展示。

复现命令：`node node_modules/typescript/bin/tsc --noEmit`、`node node_modules/vitest/vitest.mjs run`（frontend 目录）；根目录 `./gradlew.bat build`、`node scripts/verify-yaml-transfer.mjs`。浏览器脚本使用独立 Edge 与工作区夹具，需要本机已安装 Edge；不复用真实认证信息。
