# 阶段 10.4 中文文档与教学示例验收

[项目入口](README.md) · [Wiki 首页](docs/wiki/README.md) · [开发文档](DEVELOPER.md)

本轮仅文档、教学示例与验证，不改生产功能、不部署 JAR、不操作原服务器数据。适用 DialogCanvas 0.1.0 / Paper 26.1.2 Build 74，核对日期 2026-10-16。

## 交付目录

| 入口 | 内容 |
| --- | --- |
| [README.md](README.md) | 简介、能力、环境、快速开始、目录和限制 |
| [docs/wiki/README.md](docs/wiki/README.md) | 中文 Wiki 首页，6 组、31 篇章节；加首页共 32 页 |
| [01 入门](docs/wiki/01-start/overview.md) | 安装、目录、首次密码、登录、十分钟第一个菜单 |
| [02 编辑器](docs/wiki/02-editor/management.md) | 管理、四类输入、图标/正文、文本、导航/商店、撤销/草稿 |
| [03 YAML](docs/wiki/03-yaml/basics.md) | 语法、正式/手动文件、同步、分步学习 |
| [04 专题](docs/wiki/04-features/navigation.md) | 快捷入口、条件、身份、经济、PAPI、物品、限额、事务、归档/审计 |
| [05 运维](docs/wiki/05-operations/commands.md) | 全部命令权限、配置、更新备份、迁移恢复、排错和人工项 |
| [06 参考](docs/wiki/06-reference/examples.md) | 示例、完整字段/条件/动作、表达式、53 个错误码、术语和验收 |
| [DEVELOPER.md](DEVELOPER.md) | 架构、固定依赖、构建、REST、线程/存储/事务、实际内部 SPI 和未提供接口 |
| [examples/wiki/README.md](examples/wiki/README.md) | 十份独立、完整、详细中文注释教学 YAML 的安装与影响 |

Wiki 每页有版本、前置知识、相关章节、返回首页和相邻章节导航。相对链接可随仓库移动。入门页使用本轮实际截图，不虚构游戏画面；截图版本为 0.1.0 / 2026-10-16。

README/Wiki 是服主入口；WEB_API 是精确 REST 契约。FRONTEND、TEXT、YAML_EDITING、AUTHENTICATION 增加当前教程链接并保留技术/历史记录，旧源码模式不作为当前入口。

## 十份教学菜单

| ID | 覆盖 |
| --- | --- |
| wiki_01_notice | 最小提示与关闭 |
| wiki_02_confirmation | 确认/取消 |
| wiki_03_navigation | 多列、目标引用、退出按钮 |
| wiki_04_inputs | 四种输入控件 |
| wiki_05_text_icons | 图标、颜色、渐变、tooltip、同排正文物品说明 |
| wiki_06_conditions | 五类叶节点、AND/OR、嵌套、negate、成功/拒绝分支 |
| wiki_07_commands | player/op/console 真实命令配置 |
| wiki_08_shop | 原木买卖、动态数量/金额、确认、每日额度与冷却 |
| wiki_09_limits | 五周期与独立冷却 |
| wiki_10_player_data | 业务数据写入、增减与检查 |

导航只依赖先前编号的目标，可按 01→10 单项导入；数据检查点击前用第十例初始化。示例不含真实凭据/玩家记录，不覆盖现有菜单。校验不执行示例动作；商店、命令、发物品与数据写入的实际影响已单独标注。教学原件注释不随规范化导出保留。

## 本轮结果

| 验证层 | 结果与证据 | 边界 |
| --- | --- | --- |
| 静态文档检查 | PASS；`build-reports/wiki-docs-results.json`；相对链接/图片、导航、模型字段名、权限、53 个错误码、代码块与教学原件一致 | 不是模型求值测试 |
| 生产解析/校验/仓库，MockBukkit | **3/3 通过，0 跳过**；`WikiExamplesTest` XML/HTML 报告 | 十份 YAML 全集引用、按序导入、正式 JSON/规范化 YAML 一致、网页保存、导出重导入、仓库重启；不执行商品/命令链 |
| 独立 Paper Build 74 + Javalin | **7 项通过**；`build-reports/wiki-paper-results.json` | 新工作区实例、全新配置/世界/端口，仅无交易教学菜单，无玩家 Bot |
| 独立 Edge + JAR 内页面 + 真实 REST | **5 项通过，0 脚本异常**；`build-reports/wiki-browser-results.json`；实际截图已查看 | 不是模拟 API；不代表原版客户端验证 |
| JAR 校对 | 本轮未变更；现有 JAR SHA-256 与隔离运行副本一致 | 不打新生产功能、不部署原服务器 |

Paper 实际检查：首次随机密码仅输出一次、先持久化哈希且不写明文；控制台本机地址/页面/登录；浏览器新建、改 ID/标题、添加正文、校验/保存/重读；正式 JSON 与注册表一致且 YAML 已同步；手改 YAML 后网页保存被 409 拒绝；控制台 reload 导入更新、revision 改变；正常关闭释放端口；重启后哈希不变、不重新输出首次密码，旧会话 401，重新登录读取菜单和同步状态正常。

浏览器另核对草稿 YAML 下载、真实服务导入预览和取消保留当前文档。截图标注实例 ID 为 wiki_quickstart；关闭按钮保持默认文字。教程的游戏打开步骤未冒充本轮已执行。

首次浏览器脚本把成功文案错误匹配为“校验通过”，实际为“配置检查通过”；修正验证脚本后整流程重新执行通过，未因此改动生产代码。

隔离夹具沿用 `minecraft:flat` 和空 `generator-settings={}`，新世界初始化日志出现 `No key layers in MapLike[{}]`。Paper 随后正常启动，以上流程及两次停服均完成；本记录不声称整份服务端日志零 ERROR。这是夹具世界生成配置的限制，本轮未修改生产功能或原服务器配置。

JAR 仍为 `plugin/build/libs/DialogCanvas-0.1.0.jar`，工作区 `dist` 副本相同；SHA-256：`8b2357f1bff068aa97089ddd988bae8c319a0165fb5ac3b757d42f23c5b00423`。本轮仅执行 Gradle 定向测试，无前端/生产变更，未重跑前端全套测试、TypeScript 或完整构建；不把阶段 10.3 的历史计数冒充本轮结果。

## 复现

```text
python scripts/build-wiki.py
python scripts/verify-wiki-docs.py
./gradlew.bat :plugin:test --tests dialogcanvas.web.menu.WikiExamplesTest --rerun-tasks
python scripts/verify-wiki-paper.py
```

Java 环境设为 JDK 25；Gradle 首次需下载或已有依赖缓存。最后一步前提是工作区已有 Build 74 夹具 `build/stage4-paper`、构建 JAR、脚本所列 JDK 和 Edge；缺少时不能声称通过。脚本不接受生产目录参数，创建唯一工作区实例，初始测试密码只在内存，通过 stdin 交给浏览器，不在参数/报告里记录。脚本会停止自己启动的 Paper/浏览器，不清理原服务器。

## 待人工验证与剩余限制

- 游戏客户端：`/dcmenu open <id>`、Shift+F、原生排版、图标、附魔光效、文本与输入表现。
- 真实 Vault/PAPI：实际版本组合、已安装扩展、玩家动态值、余额/背包和失败补偿；本轮不执行真实交易。
- 原版及其他插件命令：真实目标权限与临时 OP 语义，尤其目标插件延迟执行；文档验证不运行 OP/console 教学命令。
- 自定义物品、玩家数据及限额：测试账号的实际执行；教学文件通过校验不代表已真实发货/落玩家状态。
- 此轮未重新验证所有阶段 7/8 故障场景、硬断电或其他 Paper/混合模组版本。已有审计证据仍保留，不将本轮文档工作解释为新增保证。

无生产功能扩展、无原服务器配置/菜单/玩家数据操作、无部署。
