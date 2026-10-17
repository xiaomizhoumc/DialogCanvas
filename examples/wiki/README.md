# 中文 Wiki 教学菜单

[返回 Wiki 首页](../../docs/wiki/README.md) · [项目入口](../../README.md) · [完整示例索引](../../docs/wiki/06-reference/examples.md)

**前置知识：** 已安装兼容版本（见项目入口及最终验收）并能登录或操作控制台。这里是十份完整 UTF-8 YAML 教学原件，详细中文注释不属于运行模型。不得覆盖服主现有同名菜单；无真实凭据或玩家数据，wiki.* 为点击后才写入的演示键。

## 安装与打开

当前 1.0.0 JAR 已内置这十份原件：启动时在 `plugins/DialogCanvas/examples/menus/` 自动生成，并附 `examples/使用说明.md`；后续只补齐缺失文件，已有文件不覆盖。生成不会自动加载或执行动作。以下导入流程用于按需启用，教学原件不受网页重新导出影响。

优先逐项用浏览器“导入 YAML”→核对 ID/标题/警告→确认草稿→保存。也可逐个复制 `menus/<id>.yml` 到插件根目录 `plugins/DialogCanvas/menus/<id>.yml`，再控制台 `dcmenu reload <id>`。文件名与根 ID 必须相同，不复制到正式 JSON 目录，不手写同步基线。

**按 01～10 顺序导入**，其中03必须在01/02之后；其余没有 open_menu 依赖。06的数据检查运行前先用10初始化。只做配置校验/导入不执行任何业务动作。打开格式：游戏 `/dcmenu open <id>`，控制台 `dcmenu open <id> <完整在线玩家名>`，需现有管理权限。

| ID | 文件 | 预期及依赖 |
| --- | --- | --- |
| wiki_01_notice | [YAML](menus/wiki_01_notice.yml) | 最小提示，只展示/关闭 |
| wiki_02_confirmation | [YAML](menus/wiki_02_confirmation.yml) | 确认/取消；确认只发教学消息 |
| wiki_03_navigation | [YAML](menus/wiki_03_navigation.yml) | 两列导航到01/02，退出独立槽位 |
| wiki_04_inputs | [YAML](menus/wiki_04_inputs.yml) | 四种输入；消息显示数量，不保存备注 |
| wiki_05_text_icons | [YAML](menus/wiki_05_text_icons.yml) | 渐变、图标、tooltip、正文物品；不发剑 |
| wiki_06_conditions | [YAML](menus/wiki_06_conditions.yml) | 五类条件；PAPI按钮需扩展，数据按钮先用10 |
| wiki_07_commands | [YAML](menus/wiki_07_commands.yml) | 实际分发三种身份命令，只发消息；OP/console有管理条件 |
| wiki_08_shop | [YAML](menus/wiki_08_shop.yml) | **真实买卖**：原木买16/卖8；需要Vault桥和经济提供者 |
| wiki_09_limits | [YAML](menus/wiki_09_limits.yml) | 五周期；**真实给石头**，不扣钱 |
| wiki_10_player_data | [YAML](menus/wiki_10_player_data.yml) | **真实修改自己的 wiki.* 数据**，先初始化后检查 |

数量 amount=8 时商店买入花128、卖出得64；不足余额/库存不得错误显示成交。消息数值模板与PAPI不同，不依靠余额变量防止扣款超额。买入每日32个、冷却2秒，出售不会返还购买额度。

## 变更与安全

只在测试账号验证真实交易、命令、物品和玩家数据。校验器不模拟经济服务或PAPI输出，不证明实际客户端已验证。特殊商品原型要一致；本包不会安装第三方依赖。

菜单保存/导入规范化导出会丢手写注释和原排版；保留本教学目录原件。无历史回退功能，不手改正式JSON/同步摘要。ID/按钮ID/规则名/scope变化会改变限额身份，正式服勿随意改名重置。

## 校验复现

`./gradlew.bat :plugin:test --tests dialogcanvas.web.menu.WikiExamplesTest --rerun-tasks` 使用现有生产解析器、校验器和Repository，对完整集合/引用及逐项导入往返验证，**不执行示例动作**。`python scripts/verify-wiki-docs.py` 检查文档/示例一致与链接。具体结果见 [STAGE10_DOCUMENTATION.md](../../STAGE10_DOCUMENTATION.md)。

**相关章节：** [YAML教程](../../docs/wiki/03-yaml/tutorial.md) · [条件与动作参考](../../docs/wiki/06-reference/conditions-actions.md)
