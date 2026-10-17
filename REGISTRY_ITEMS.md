# 物品注册名与无贴图编辑器

## 如何填写

- 正文物品：选中“物品展示”，在“物品注册名”填写 `iron_ingot`。
- 按钮图标：选中按钮，在“按钮图标注册名”填写 `minecraft:diamond_sword`。按钮文字独立编辑；“清除图标”只移除图标。
- 出售数量上限：选中数量输入，在“背包上限物品注册名”填写 `oak_log`；不需要时点击“清除背包上限”。
- 给物品、收取物品动作沿用已有注册名输入和自定义名称、描述、真实附魔及属性功能。

无命名空间的小写名、`minecraft:` 前缀和已有大写 Material 都可以使用。填写后离开输入框或按 Enter 应用，按 Esc 取消。输入过程中合法值即时预览，未应用时不进入文档撤销历史；非法值保留在输入框并阻止保存旧值。注册名是否存在最终由服务器校验。

## 网页与游戏的区别

网页不加载 Minecraft 纹理，使用“物”文字标记，悬停显示名称和注册名。没有图片选择、分类或图片搜索，不会请求外网纹理。

游戏正文继续通过已有 ItemStack 渲染，按钮继续通过已核实的原生 Sprite 映射调用客户端纹理。JAR 无须内嵌 PNG。不改写菜单文件，不改变文字样式、独立图标、正文光效或实际发货配置。

按钮 Sprite 是图集组件，不等于箱子界面的完整物品模型：仍需明确映射，特殊模型和插件私有注册名不能保证生成图标。未知图标给出提示并保留按钮文字，不猜测纹理路径。游戏内效果需要原版客户端复核。

## 开发与打包

前端使用 RegistryItemField、ItemRegistryPreview，移除 MaterialPicker、MaterialThumbnail、重复前端纹理目录和附魔动画 CSS。保留后端 1423 项 atlas/sprite 映射，移除 thumbnail URL；`/api/meta` 返回映射信息，不提供 PNG。

`scripts/update-vanilla-icons.py` 仍从固定官方客户端模型及图集生成映射，但不复制图片。来源记录在 `scripts/vanilla-icon-source.json`。`python scripts/verify-vanilla-icons.py` 检查无 PNG 约束和映射；没有官方客户端缓存时，额外来源重核会明确跳过。

Gradle 重建构建资源输出中的 web 副本，再合并原有源资源与当前 dist，避免删除输入后留下旧 JS/CSS/PNG。不会清空 `plugin/src/main/resources/web`。Shadow 同时排除旧 `web/vanilla/`。

## 验证范围

结果记录在 `build-reports/registry-inputs-acceptance.json`。浏览器测试使用真实生产前端、隔离 Edge 和模拟 REST，只验证交互；生产 Javalin 元数据、资源处理和原生 Sprite 使用 Java 测试验证。本轮不启动真实 Paper、不连接游戏客户端、不执行买卖、不部署原服务器。

已有审计日志完整性测试包含 800 次逐行强制刷盘，在完整套件中出现 10 秒等待超时；仅将测试等待改为 30 秒，保留日志数量、去重、合法 JSON 等断言，不修改生产审计逻辑。4 项既有 MockBukkit 跳过不计为通过。
