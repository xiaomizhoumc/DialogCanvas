# 阶段 8.1：现有画布实时联动

## 编辑规则

- 在右侧输入合法文字或数字时，中间画布立即显示临时效果，不必先点击别处。画布上会出现“正在临时预览”的提示。
- 普通文字离开输入框后提交；数字离开输入框或按 Enter 提交。一次连续输入形成一次菜单撤销点。Esc 放弃该字段尚未提交的修改。
- 数字暂时为空、只输入负号、指数不完整或超出范围时，保留输入并提示；画布使用已提交的值，不产生 NaN、Infinity 或错误尺寸。像素尺寸、数量、列数要求整数，滑块范围允许小数。需要去掉可选数值时使用“恢复未设置”，不把暂时清空误当作删除配置。
- 颜色/渐变窗口只预览选区，画布同步显示临时效果；点击“应用”才提交一次。取消或 Esc 恢复打开前的文字效果。窗口前已输入但未提交的文字仍保留。清除颜色保留其他样式；“清除样式”沿用现有语义。
- 图标、材质、复选框、下拉选项仍是选择后立即提交的离散操作，每次操作进入现有菜单撤销历史。
- 失焦时合法输入先提交，再进行正常切换/保存操作；直接切换选中项、撤销、重做、重新读取、删除/排序等文档结构操作会清理临时预览。重新读取仍沿用未保存确认。
- 尚未提交的草稿不改变正式文档、服务端基线、revision 或正式同步状态；关闭页面、退出、切换菜单时仍保护它们。保存/校验前必须完成提交或取消；非法数字不会悄悄保存旧值。
- 保存进行期间继续输入的新草稿不会被旧响应覆盖；响应仍更新服务端基线和 revision。取消新草稿返回此前已提交的值。

## 覆盖核对

| 项目 | 实时效果及边界 |
| --- | --- |
| 菜单标题、文字正文 | 统一 TextSpec 解析、颜色、渐变、换行和已支持样式；输入未失焦即预览 |
| 正文物品 | 现有材质、光效、尺寸即时更新；自定义名称和 lore 以富文本悬停提示显示；右侧说明共用文字解析 |
| 输入标签与选项文字 | TextEditor 草稿即时预览；初始文字/滑块数值、初始勾选、初始选项随配置刷新 |
| 输入尺寸和范围 | 合法草稿即时预览；保留默认未配置状态；非法数值保留输入和提示 |
| 按钮文字、tooltip | TextEditor 草稿即时预览；tooltip 可在输入框仍有焦点时通过悬停检查 |
| 图标 | 与标签独立；沿用服务端确认的 iconMappings、既有缩略图及占位；不新增 Sprite 推断 |
| 布局 | 原 notice/confirmation/multi_action 结构、列数、按钮宽度、退出按钮、正文/输入顺序；未新增坐标画布 |
| 不支持的文本 | 复杂 Component、未知标签保持原配置，明确提示；只显示安全 React 节点，不执行交互 |
| 原生细节 | 多行输入尺寸、labelFormat、物品装饰、字体/缩放/工具提示仍不是完整原生模拟；不承诺像素一致 |

没有新增 lore 属性编辑入口；已有 lore 在读取时参与悬停显示。逻辑、权限、PAPI、命令、经济和背包操作不在网页执行，也没有游戏预览接口。网页变量保持原文。

## 实现位置

- `frontend/src/state/livePreviewStore.ts`：独立临时字段覆盖，不进入 zundo；只复制修改路径，保留其他模型节点引用。
- `frontend/src/state/useFieldPreview.ts`：字段所有者和卸载清理。
- `LiveDialogPreview.tsx`：仅预览区域订阅覆盖，避免每次输入使整个 App 和属性面板重渲染。
- `TextEditor.tsx`：按动画帧合并实时转换；复用保存使用的 `preserveUnmodifiedText`，输出依旧是 TextSpec。
- `gradientExtension.ts`：同一份颜色/渐变事务逻辑；临时效果构造未分发的 ProseMirror transaction，不修改真实编辑器或本地历史。
- `DialogPreview.tsx`：复用文本解析并缓存未改内容；受控的只读输入随配置变化；物品/按钮提示共用富文本显示。
- `editorStore.ts`：取消、选择、加载和撤销时清理临时层；旧保存响应不替换存在新草稿的正式对象引用。

临时层只在内存中存在，不写 HTML、TipTap JSON、JSON/YAML、认证数据或 localStorage。原单 JAR 静态资源打包流程保持。

## 验证和复现

```powershell
cd E:\project\menu\frontend
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=../build-reports/live-preview-frontend-results.json
node node_modules/vite/bin/vite.js build
cd ..
node scripts/verify-live-preview.mjs
```

构建使用现有 Java 25 和 Gradle Wrapper：`./gradlew.bat build --offline --no-daemon`；依赖缓存与 JVM 参数按本机开发说明配置。

- 前端自动测试：153 项通过，其中新增 16 项覆盖临时层与基线/历史隔离、合法/非法数字、生命周期清理、旧响应、纯渐变事务、结构和文本输出。
- TypeScript、Vite 与完整 Gradle build 通过。Java 497 项：493 通过、4 项既有跳过，不将跳过记为通过。
- 独立 Edge 实际运行生产前端资源，使用本机模拟 REST：实时标题/正文/按钮/tooltip、数字空值/非法值、失焦一次提交、Esc、初始输入值、颜色/渐变临时应用取消、保存 TextSpec、撤销重做、旧保存响应及生命周期清理。结果为 `build-reports/live-preview-browser-results.json`，截图为同目录 `live-preview-browser.png`。
- 本轮实时流程 11 项、原有渐变选区回归 19 项浏览器检查均通过；后者覆盖跨样式/段落、Unicode、部分替换、未知原文保护及选区恢复，结果 `gradient-picker-results.json`。
- 性能检查为约 3500 字符文本追加 10 次，记录每次事务至两个渲染帧的耗时；含浏览器帧间隔，不是纯函数基准或所有硬件性能保证。详见浏览器报告。
- JAR 内前端资源逐字节检查结果见 `build-reports/live-preview-results.json`。

最新构建及导出 `plugin/build/libs/DialogCanvas-0.1.0.jar`、`dist/DialogCanvas-0.1.0.jar`，SHA-256 为 `5f6989cec5fb1940596544225b32cceec9c94be615809a6578d81f840edeb88a`；1050 个网页资源逐字节一致。既有 Vite 大包体提示仍存在，本阶段不调整依赖或分包策略。商店安装 ZIP 沿用上一阶段，未重新打包。

本轮浏览器使用独立资料目录，无真实服主会话。未连接 Minecraft 客户端，未在原服务器验收、未部署原服务器 JAR 或修改其数据；游戏像素、真实购买和未知文本/超大菜单的性能不列为本轮已验证项。
