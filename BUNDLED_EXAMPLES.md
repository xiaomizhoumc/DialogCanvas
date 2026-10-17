# 内置中文注释教学示例

[项目入口](README.md) · [示例索引](examples/wiki/README.md) · [中文 Wiki](docs/wiki/README.md)

当前 1.0.0 JAR 内置原有十份教学菜单，其中第08份是橡木原木买卖商店。首次启动生成：

```text
plugins/DialogCanvas/examples/
├── 使用说明.md
└── menus/
    ├── wiki_01_notice.yml
    ├── wiki_02_confirmation.yml
    ├── wiki_03_navigation.yml
    ├── wiki_04_inputs.yml
    ├── wiki_05_text_icons.yml
    ├── wiki_06_conditions.yml
    ├── wiki_07_commands.yml
    ├── wiki_08_shop.yml
    ├── wiki_09_limits.yml
    └── wiki_10_player_data.yml
```

每个实际配置字段旁补充中文用途、适用位置、单位、输入引用及风险说明；原有场景注释保留，YAML 字段与语义没有改变。原始教学文件从 JAR 按 UTF-8 字节复制，不经过规范化导出，因此能保留所有注释。

## 如何启用

1. 在服务器电脑执行控制台 `dcmenu editor` 并登录网页。
2. “导入 YAML”选择生成的文件，核对预览和依赖，确认进入草稿后校验、保存。
3. 游戏管理员 `/dcmenu open wiki_01_notice`；控制台为在线玩家打开时使用 `dcmenu open wiki_01_notice 玩家名`。
4. 全部导入按01→10顺序，第03份导航依赖01/02。也可复制到正式手动入口 `plugins/DialogCanvas/menus/` 后用已有 `dcmenu reload <id>` 导入。

文件生成本身不加载菜单或执行交易。第08份依赖 Vault 经济服务，第06份的 PAPI 条件需要对应扩展；第07份会分发命令，第09份会发物品，第10份会写自己的教学数据。请用测试账号按需启用。

## 保留与失败规则

- 后续启动只补齐缺失项，已有教学文件和使用说明均不覆盖；正式菜单及玩家数据完全不受生成流程影响。
- 生成调用在既有初始化工作线程，文件使用现有 AtomicFiles 原子写入；不新增菜单模型、解析器、同步状态或导入路由。
- 目标在检查后被手动创建时拒绝覆盖；拒绝符号链接/目录重定向。不支持原子移动时不降级普通覆盖。
- 某文件写失败输出 `EXAMPLE_INSTALL_FAILED` 及固定相对文件名，不输出异常原文；其余初始化继续，下一次启动可补齐缺失文件。
- 不监控、不自动加载教学目录。正式 JSON、YAML、revision、同步基线、迁移与冲突规则保持原有流程。
- 正式菜单通过网页保存或重载后规范化 YAML 会丢失手写注释；教学原件在 examples/ 中保留，不会被这些操作覆盖。

## 验证范围

`BundledExamplesTest` 验证十份资源与安装文件逐字节一致、生产 YAML 可解析、重启保留手改文件、仅补缺失项、写入失败重试、创建竞争及目录重定向保护；`WikiExamplesTest` 使用既有生产模型/校验器/Repository 验证完整集合引用及 JSON/YAML 往返。

工作区独立 Paper 26.1.2 Build 74 已验证新1.0.0 JAR两次启停：生成十份YAML和使用说明、正式菜单列表不会自动新增、正常API保存仍同步、重启保留手改原件/说明并补齐缺失项、正式JSON/YAML未被改动、关闭释放端口。报告 `build-reports/bundled-examples-paper-results.json`。本轮未重新执行其他三版运行时、原版客户端或真实交易验证；不替代阶段10.5的既有范围说明。

未部署原服务器，不修改其配置、菜单或玩家数据。最新JAR、SHA-256和最终构建状态以 `dist/DELIVERY-1.0.0.json` 为准；旧0.1.0 ZIP是历史产物。
