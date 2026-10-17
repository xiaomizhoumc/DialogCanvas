# 06.7 文档与教学示例验收

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 区分本轮自动校验与历史验收。

## 本轮范围

阶段 10.4 只修改文档、教学示例及其验证脚本/测试；阶段 10.5 完整构建并验证同一份 JAR 的四版兼容性，不改变生产功能，不部署原服务器，不访问其配置/菜单/玩家数据。入口 README.md、Wiki 首页与 DEVELOPER.md；旧专题按权威来源链接，不把早期计划当现在教程。

## 证据与复现

- `build-reports/wiki-docs-results.json`：内部相对链接、当前字段/命令/权限、教程代码与文件一致、完整示例和敏感数据约束的静态检查。
- `WikiExamplesTest`：现有 MenuYaml + MenuValidator + MenuRepository，验证十份完整 YAML、全集引用、逐项导入、导出重读；只配置，不执行商品或命令链。
- `build-reports/wiki-paper-results.json`：全新工作区隔离 Paper 的安装/首次密码/真实 REST 创建保存/手改 YAML/reload/重读/重启验证；只用无交易提示菜单。
- `build-reports/wiki-browser-results.json`：最终前端在同一隔离实例登录、新建、改标题、校验、保存/重读和 YAML 导入导出。浏览器独立实例不复用真实服主会话。

报告中 PASS 才算通过；未执行/跳过单独标记，不拿其他阶段数字冒充本轮结果。最新完整构建与四版兼容以 [最终交付验收](../../../STAGE10_ACCEPTANCE.md) 为准；[文档专项](../../../STAGE10_DOCUMENTATION.md) 保留阶段 10.4 的范围。

## 未验证项

不在文档验证中执行真实买卖、OP/console命令、发物品、写玩家数据动作或玩家 Bot。动态 PAPI 需要实际扩展/玩家，真实 Vault 余额/背包/补偿和客户端显示仍待服主在测试账号验证；没有硬断电实验。示例解析/保存通过不代表这些效果已验证。

## 相关章节

[项目入口](../../../README.md) · [开发复现](../../../DEVELOPER.md)

---

[上一章：06.6 术语表与资料权威顺序](glossary.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
