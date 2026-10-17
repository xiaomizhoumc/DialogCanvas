# DCMenu 1.0.0 功能示例：10 个带详细注释的菜单

适配当前 **DialogCanvas / DCMenu 1.0.0，Paper 26.1～26.3**；插件为 `DialogCanvas-1.0.0-Paper-26.1-26.3.jar`。编译/本包自动测试仍使用固定 Paper 26.1.2 Build 74 API，兼容范围沿用主项目已记录的验收，不表示本轮重新测试所有版本。本包不包含/替换 JAR，不修改配置、密码、玩家数据或旧商店。

## 本次更新

- 物品正文、按钮图标、背包上限、发货/收物品示例改用 `minecraft:oak_log` 等注册名；另保留 `oak_log`、`iron_ingot` 的小写简写示例。字段仍叫 `material` / `inventoryMaximumMaterial`，不是新增 `registryId` 字段；旧大写 Material 仍兼容。
- 与当前无缩略图编辑器一致：网页是文字占位与注册名输入，没有选图器、PNG 依赖或外网贴图。游戏仍由客户端渲染正文物品及已映射的按钮 Sprite，不能用网页占位判断游戏效果。
- 补齐当前网页 YAML 导入/导出、旧示例安全升级、控制台为指定在线玩家打开的步骤。保留十个原 `examples_*` ID、按钮 ID、限购规则/scope、原木价格与额度，不以换名清空限购。

这是上一版功能包的更新，**不是 JAR 内置的 `wiki_01_*`～`wiki_10_*` 教学原件**。后者只在 `plugins/DialogCanvas/examples/` 补齐缺失项，不自动更新已有文件；本包不会覆盖那个教学目录。这里只启用本套十个菜单即可，不必同时导入两套。

## 菜单一览

| 文件 / 菜单 ID | 主要内容 | 外部依赖 / 实际影响 |
| --- | --- | --- |
| examples_home | 导航、图标、提示、布局、退出槽位 | 只打开界面；不占用现有快捷主菜单 |
| examples_text | notice、plain/legacy/MiniMessage/Component、三色渐变、物品正文/name/lore/description | 只展示，不发货 |
| examples_inputs | bool、文本、多行、数值手输、滑块、单选、输入条件、数值消息/算式 | 不扣款；备注不保存、不插进消息 |
| examples_log_shop | 橡木原木双向买卖、手输/滑块、背包动态上限、共享日限购/冷却、刷新 | 真实扣款、发货、收物品、充值；需要经济服务 |
| examples_supply_shop | 石头/面包/铁锭/火把、自定义武器、全部五种限购周期 | 真实扣款、发货；需要经济服务 |
| examples_confirm | confirmation、yes/no、denyActions、WAIT_FOR_RESPONSE、自身引用 | 写点击者自己的 examples.accepted_rules |
| examples_conditions | AND/OR、权限/取反、数字/字符串、可选 PAPI 条件和消息 | 普通按钮不依赖 PAPI；余额按钮需要对应扩展 |
| examples_player_data | 固定标量/对象写入、数值增减、player_data 条件、独立冷却 | 只改点击者的 examples.* 演示数据 |
| examples_commands | player/op/console、动态 token、固定 JSON 参数、权限门 | 只发聊天信息；OP/console 按钮需 dcmenu.admin |
| examples_flow | 延迟、概率、return、声音、刷新、none、close_menu/纯关闭 | 无经济/物品奖励；概率只作用于消息 |

共 **10 个逻辑菜单、10 份 YAML**。ZIP 另含同一批菜单的正式 JSON 和 SHA-256 同步基线，不是额外菜单。

## 推荐安装：使用完整 ZIP，停服后复制

这些菜单互相导航，不能依赖“先随便导入一个，再逐个导入”的方法：首次导入时目标菜单可能还不存在。完整 ZIP 为同一批 YAML 生成正式 JSON/同步基线，启动时可一次验证整个集合。

1. 先在测试服操作。正常停服并备份现有 `plugins/DialogCanvas`；确认本服没有 `examples_*` 同名菜单。若已存在，先备份/解决冲突，不直接覆盖已有文件。
2. 解压 `DCMenu-FeatureExamples-1.0.0-Paper26.1-26.3.zip`，将包内 `plugins/DialogCanvas/` 下的三个目录合并到插件数据文件夹，保留原文件，不删除其他菜单：

   ```text
   plugins/DialogCanvas/
     menus/examples_*.yml             # 10 份详细中文注释的可读配置
     data/menus/examples_*.json        # 相同 10 个菜单的正式文档
     data/menu-sync/examples_*.json    # 配套同步摘要，不要手工改
   ```

3. 启动服务器。管理员/OP 在游戏执行 `/dcmenu open examples_home`；控制台执行 `dcmenu open examples_home <完整在线玩家名>`。普通玩家可通过原有主菜单的 open_menu 按钮进入；这里不自动授予 dcmenu.admin、dcmenu.command.open 或 dcmenu.shortcut。细分 open 权限还允许为其他在线玩家展示，勿当成“只能打开自己的界面”的权限。
4. 默认没有开启新的快捷主菜单。如果希望用本示例替换快捷入口，先在网页取消旧主菜单的快捷开关，再给 examples_home 开启并保存；不要让两个菜单同时占用快捷入口。

若 config.yml 使用自定义 `data-directory`，将包内 `data/menus` 与 `data/menu-sync` 的内容复制到实际数据目录的同名子目录；手动 YAML 仍位于插件目录 `menus`。不要把 ZIP 的固定 data 路径误当作自定义配置的真实路径。

包根的 `版本清单.json` 记录版本、菜单 ID 和每份 YAML/正式 JSON 的 SHA-256，可用于核对文件，不放进插件配置目录。

## 已安装旧 examples_*：只升级 YAML，不覆盖正式 JSON/基线

1. 先备份。逐项比对已编辑的菜单；有自定义价格、条件、商品或布局时只合并需要的片段，不直接覆盖。
2. 网页使用“导入 YAML”，选本包 `plugins/DialogCanvas/menus/<id>.yml`，核对预览及同名状态，确认建立草稿，检查后再点保存。导入不自动保存/执行动作；取消保留旧正式菜单。同名替换沿用 revision 冲突和 metadata 维护，遇冲突先重读/核对，不强行重试。
3. 控制台替代方案：关闭该菜单网页编辑页，将合并后的单份 YAML 放到现有 `plugins/DialogCanvas/menus/<id>.yml`，执行 `dcmenu reload <id>`；逐项核对结果。所有旧 ID 已存在时，不存在首次安装的相互引用问题。
4. **已有菜单不要用本包 data/menus 和 data/menu-sync 覆盖升级**：它们仅用于无同名菜单的首次安装。不要删除 playerdata、transactions、recovery，也不要为了“重新导入”改菜单/按钮/scope 来清空已用额度。

若首次安装只想走网页：先创建全部十个同名占位菜单（可用仅关闭的 notice），保存后逐份导入本包 YAML，再保存替换。直接任意逐项导入会因导航目标还不存在而被拒。网页只能选单个 YAML，不能直接选择整个 ZIP；打开编辑器可用 `/dcmenu edit`，查看已加载菜单可用 `/dcmenu list`，都需要已有管理权限。不要把文件放进旧版迁移目录，不在运行时手改正式 JSON/同步基线。

## 修改与注释保留

- 学习时优先阅读本目录 `menus/*.yml`。每份文件的开头说明依赖，关键字段说明单位、类型、执行顺序与边界；没有 YAML 锚点或别名，复制片段即可理解。
- 安装完成后，关闭该菜单的网页编辑页，修改服务器 `menus/<id>.yml`，再从控制台执行 `dcmenu reload <id>` 导入。一次修改一个菜单，处理好版本/待导入冲突；不会自动监听文件变化。
- 网页保存或导入可能重新导出 YAML，**不承诺保留手写注释/原缩进**。请保留本示例源码作为注释模板；不要把生产同步文件当作注释的唯一副本。
- 修改源示例后，需要重新生成完整 ZIP 才能配套更新正式 JSON 与基线。不要只改包内 YAML 而继续安装旧基线。
- metadata 由服务端保存流程维护；按钮/菜单/限购 key 与 scope 已上线后改名会改变计数身份。修改金额还应同步修改按钮标签和成交消息；标签不会自动推导价格。

## 经济与商店注意事项

- 原木示例固定为**普通橡木原木，买入/回收均 1 游戏币/个**。本套只有紧凑的商店示例，不是之前 787 种商品的综合商店。
- 经济动作需要本服已配置的 Vault 兼容桥和真正的经济提供者。只安装桥而没有经济插件不能交易；本包不会安装或配置这些依赖。
- 商店不依靠 PAPI 做余额判定，真正扣款会检查余额。不足时不发货；发货失败尝试补偿，不能保证跨插件/多文件原子性。不确定交易需核查 `data/transactions`、`data/recovery` 和插件根目录 `playerdata`，不要删除记录后盲目重试。
- 手输和滑块是独立输入，各按钮取各自的值，不会自动互相同步。每次点击会校验整个表单的输入，所以其他数字框也须保持合法。
- 出售上限取“打开时/刷新时”的匹配普通物品库存，只统计主背包存储区。交易时会再次检查真实库存，旧界面不能凭旧数量多拿钱；成交刷新会重置输入和确认框。
- 原木购买两种方式共享每天 128 个与 2 秒冷却。售出不返还已用购买额度。库存上限不等于动态剩余限购上限，后者在业务提交时检查。
- 自定义武器展示当前注册名、名称、lore、真实附魔、属性和不可损坏能力；不支持随便填写某插件的私有物品 ID。需要回收特殊武器时，take_item 的完整原型须匹配，不能仅靠原木式材质数量上限来判断。

## 条件、消息和命令

- 先在玩家数据示例点“初始化/清零”，再累计和检查；不存在的数据不当作默认 0/false。初始化会覆盖本示例的三个演示键，不改其他业务数据。
- PAPI 按钮使用 `%vaultunlocked_eco_balance%` 作可选演示；若本服用其他扩展，改成扩展实际提供的未格式化数值变量，并同时修改消息中的变量。不保证该变量在每个服务器都存在。
- 未解析的占位符、非法数字或来源错误是 ERROR，不会执行成功动作，也不会执行 denyActions。普通 FAIL 才会进入 denyActions。消息占位符在状态提交后解析，解析失败不意味着状态已回滚。
- 消息的 `$(key)` 当前只支持数值输入，计算结果用外层 `$(...)`；普通文本、单选和布尔值用于条件/安全命令参数，不自动用于任意聊天模板。标题/正文/标签不自动解析 PAPI 或玩家数据。
- 固定命令参数可含中文/JSON/空格；动态输入替换必须满足单 token 安全规则。player/op/console 保持现有约定，目标命令检查选定身份的权限，临时 OP 在同步调用 finally 恢复。没有命令允许列表需要额外放行。
- 当前未支持的 dialog_list、server_links、全服限购、任意脚本、完整交易幂等等没有混入这些示例。ClickGuard 只负责执行期间去重；限购/冷却与未决交易记录承担其他保护。

## 维护者：生成和验证

在项目根目录，使用 Java 25 和当前已构建的 JAR：

```powershell
& 'E:/project/java25/bin/java.exe' --class-path plugin/build/libs/DialogCanvas-1.0.0-Paper-26.1-26.3.jar scripts/PackageFeatureMenus.java
```

生成器直接调用当前 JAR 内现有 MenuYaml/MenuDocuments 解析器，保留 YAML 原始注释字节，输出 `dist/DCMenu-FeatureExamples-1.0.0-Paper26.1-26.3.zip` 和同名 `.sha256`，不连接服务器，不复制插件 JAR，也不会重写旧版 ZIP。只有根目录的安装说明/版本清单外加同一批十个菜单的三种文件，共 32 个 ZIP 条目。

`FeatureMenuPackTest` 使用项目实际 MenuValidator、FileStorage、MenuRepository、限购与生产引擎验证结构/引用、安装基线、重读/保存、原木交易和共享额度。自动验证不等于原版客户端显示或真实 Vault/PAPI 购买实测；先在测试服人工查看，再决定上线。
