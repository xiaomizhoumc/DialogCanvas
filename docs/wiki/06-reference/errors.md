# 06.5 错误码与诊断参考

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** 先保留草稿/文件，不根据失败提示重复交易。

## REST 格式

统一 `{error,code,details?}`。details 使用 JSON Pointer，如 `/menu/actions/0/actions/1/amount`：第1个按钮成功链第2个动作的 amount。前端将路径定位到节点/字段；不要把 API 包装写进 YAML。客户端不显示堆栈、绝对路径、Token 或变量值。

HTTP 400 请求语法，401未登录，403来源/CSRF或认证操作拒绝，409冲突，413超限，415媒体类型，422模型/业务配置，428缺revision，429限流，500/503服务或存储。有效 body 仍可能有 warnings；警告不等于事务回滚。

## 共用中文反馈目录

下表由当前 `frontend/src/i18n/errorMessages.json` 生成，Java/网页共用同一来源。它覆盖公共诊断，不声称穷尽所有验证叶错误；字段级补充在后表，未知码保留诊断并要求核查，不回显原异常。

| 诊断码 | 中文说明 | 建议操作 |
| --- | --- | --- |
| UNKNOWN_ERROR | 操作未完成。 | 保留草稿，记录诊断码并联系服主核查。 |
| HTTP_413 | 请求内容超过 1 MiB 限制。 | 保留草稿，减少菜单内容后再校验；不要把图片数据放进菜单。 |
| INVALID_YAML | YAML 无法安全解析。 | 请使用 UTF-8 编码，并检查缩进、重复键及不支持的标签；当前草稿和正式菜单未改变。 |
| INVALID_JSON | JSON 不合法或含重复字段。 | 请检查手写配置的逗号、引号和重复键。 |
| INVALID_REQUEST | 请求格式不符合接口要求。 | 保留草稿并核对编辑器版本和配置字段。 |
| UNSUPPORTED_MEDIA_TYPE | 请求内容类型不符合接口要求。 | 请使用当前编辑器；YAML 导入使用 application/yaml，其他菜单接口使用 application/json。 |
| STORAGE_READ_FAILED | 正式菜单文件读取失败。 | 请检查文件是否可读、文件大小和目录权限；保留草稿。 |
| YAML_READ_FAILED | 手动 YAML 文件读取失败。 | 请检查文件是否可读及目录权限，保留手动修改。 |
| YAML_SYNC_READ_FAILED | 同步基线读取失败。 | 请保留现有文件并核查同步记录，不要删除基线来强制覆盖。 |
| YAML_LIST_FAILED | 无法读取手动 YAML 列表。 | 请检查菜单目录权限，正式菜单不会因此自动删除。 |
| YAML_IMPORT_FAILED | 手动 YAML 无法解析，尚未完成导入。 | 请检查缩进、冒号和字段类型；保留上一份可用菜单，不要删除正式 JSON。 |
| AUTH_REQUIRED | 登录已失效或尚未登录。 | 请重新登录，当前页面草稿会保留。 |
| INVALID_CREDENTIALS | 密码不正确或无法登录。 | 请核对密码，不要连续尝试。 |
| CURRENT_PASSWORD_INCORRECT | 当前密码不正确。 | 请核对当前密码；本次没有修改密码。 |
| LOGIN_RATE_LIMITED | 尝试次数过多。 | 请等待一分钟后再试。 |
| PASSWORD_MISMATCH | 两次新密码不一致。 | 请重新填写确认密码。 |
| INVALID_NEW_PASSWORD | 新密码不符合要求。 | 至少 8 个字符，UTF-8 不超过 72 字节，不使用无效字符。 |
| PASSWORD_SAVE_FAILED | 密码未能保存。 | 请联系服主检查配置文件权限后核查登录状态。 |
| PASSWORD_CONFIG_CHANGED | 密码配置已被其他操作修改。 | 请核查当前配置并重新登录，不要覆盖其他修改。 |
| ORIGIN_INVALID | 请求来源不受信任。 | 请使用控制台显示的本机编辑器地址，不要从其他网站发起操作。 |
| CSRF_INVALID | 本次请求的登录校验已过期。 | 保留草稿，重新登录后再操作。 |
| REVISION_CONFLICT | 这份菜单已被其他页面或重载操作修改。 | 草稿已保留。先复制需要保留的修改，再重新读取并合并，不要直接覆盖。 |
| YAML_SYNC_CONFLICT | YAML 有待导入修改，或同步基线无法确认。 | 草稿已保留。请先核查 JSON/YAML 和同步状态；确认手动修改后使用 dcmenu reload <菜单ID> 导入，不要反复保存或删除。 |
| MENU_REFERENCED | 其他菜单仍在打开这个菜单，暂时不能删除。 | 请查看引用菜单及字段位置，先修改引用并保存，再删除。 |
| SHORTCUT_MENU_CONFLICT | 已有其他菜单设为快捷主菜单。 | 先取消原主菜单的快捷打开并保存，再启用当前菜单。 |
| YAML_SYNC_FAILED | 正式 JSON 和内存菜单已保存，但 YAML 导出失败。 | 游戏内已使用新版本。请核查文件权限及同步状态，不要重复提交本次保存。 |
| VALIDATION_FAILED | 配置检查未通过，暂时不能保存。 | 请按下面的节点和字段位置修改，再点击“校验”。 |
| MENU_ALREADY_EXISTS | 这个菜单标识已经存在。 | 请换一个标识，或读取已有菜单后修改。 |
| MENU_NOT_FOUND | 菜单不存在或已被删除。 | 保留当前草稿，刷新菜单列表并核对菜单标识。 |
| YAML_NOT_FOUND | 没有找到这个菜单的手动 YAML。 | 请核对菜单标识及手动编辑文件，文件缺失不会自动删除正式菜单。 |
| REGISTRY_PUBLISH_FAILED | 菜单未能发布，已恢复原文件和内存菜单。 | 草稿已保留。请联系服主检查发布失败原因。 |
| STORAGE_WRITE_FAILED | 正式菜单文件写入失败，没有发布新版本。 | 草稿已保留。请检查磁盘空间和目录权限。 |
| STORAGE_DELETE_FAILED | 菜单删除未完成，正式菜单及内存状态已保留或恢复。 | 请核查文件权限和手动 YAML 状态后再操作。 |
| MENU_STATE_UNCERTAIN | 文件与内存菜单的一致性无法确认，菜单服务已阻断。 | 不要重复提交。请服主停服核查正式文件、YAML 和同步记录后恢复服务。 |
| MENU_SERVICE_UNAVAILABLE | 菜单服务尚未就绪或已停止。 | 草稿已保留。请服主检查启动日志，恢复服务后重新读取核查。 |
| AUTH_UNAVAILABLE | 登录服务暂不可用。 | 请服主检查启动日志与配置，草稿保留在当前页面。 |
| REVISION_REQUIRED | 操作缺少当前菜单版本。 | 保留草稿，先读取服务器菜单，取得版本后合并修改。 |
| INVALID_REVISION | 菜单版本信息无效。 | 请重新读取服务器菜单，不要自行修改 revision。 |
| INVALID_MENU_ID | 菜单标识不合法。 | 使用 1–64 个小写字母、数字、下划线或连字符，不能填写路径或保留名称。 |
| REQUEST_TIMEOUT | 请求等待超时。 | 保留草稿，检查服务是否正常。 |
| NETWORK_UNAVAILABLE | 与本机编辑器服务的连接中断。 | 保留草稿，检查服务器和 Web 服务是否仍在运行。 |
| INVALID_RESPONSE | 服务器响应不完整或无法解析。 | 保留草稿，联系服主核查服务状态。 |
| CONDITION_FAILED | 使用条件未满足，本次未执行成功动作。 | 请检查权限或菜单中说明的使用要求。 |
| CONDITION_ERROR | 使用条件读取失败，成功动作未执行。 | 按操作编号检查变量或玩家数据；ERROR 不执行拒绝动作链，也不扣款、发货或提交限购。 |
| TRANSACTION_ROLLED_BACK | 操作未完成，已完成可撤销数据的补偿。 | 核查交易失败原因；此结果不代表外部不可逆效果已撤销。 |
| TRANSACTION_REJECTED | 操作未被接受。 | 核查服务状态和输入，不将拒绝描述为已经回滚。 |
| INSUFFICIENT_FUNDS | 余额不足，无法完成购买。 | 核对商品价格与购买数量；本次可撤销修改已补偿。 |
| RECOVERY_REQUIRED | 交易结果待核查，不能确认补偿完成。 | 请勿重复点击。按操作编号核查交易、限购和恢复记录；保留现有阻断。 |
| RECOVERY_SAVE_FAILED | 补偿未完成，恢复记录也未能完整保存。 | 请勿重复点击。立即核查磁盘、交易意图及相关账户/物品，无法确认补偿完成。 |
| POST_COMMIT_FAILED | 数据操作已提交，但后续消息、命令或菜单更新失败。 | 扣款或发货不会因此自动回滚。按操作编号核查，不要重复提交或自动重放。 |
| PRODUCTION_OPERATION_FAILED | 操作收尾失败，最终结果需要核查。 | 按操作编号检查交易记录，勿重复购买；不将此结果描述为已回滚。 |
| MENU_DISPLAY_FAILED | 菜单展示未完成。 | 核查菜单配置、玩家状态和服务日志，未自动重试。 |
| MANAGEMENT_OPERATION_FAILED | 管理操作未完成。 | 核查诊断码与审计结果，不要根据异常原文推断提交状态。 |

## 常见 details 码

REQUIRED（必填缺失）、INVALID_FIELD（未知/类型错）、INVALID_MENU_ID（ID）、UNSUPPORTED_VERSION（版本）、UNSUPPORTED_CAPABILITY（尚不支持）、UNRELATED_FIELD（类型不适用）、OUT_OF_RANGE（范围）、DUPLICATE_KEY（重复ID/key）、DUPLICATE_INITIAL（多个单选初始）、INVALID_TEXT（文本）、UNKNOWN_MATERIAL/UNKNOWN_SOUND/UNKNOWN_ENCHANTMENT/UNKNOWN_ATTRIBUTE（服务器目录无此项）、INVALID_OPERATION/INVALID_SLOT（属性选项）、INVALID_EXPRESSION/INPUT_NOT_FOUND（表达式/输入引用）、MENU_REFERENCE_NOT_FOUND（目标不存在）、INVALID_YAML（安全解析/编码）、UNSUPPORTED_MENU_FORMAT（非当前根格式）。

具体条件结构诊断来自 ConditionResult.errorCode，包括 EMPTY_GROUP、INVALID_COMPARISON、INVALID_SOURCE、TREE_DEPTH_LIMIT/TREE_NODE_LIMIT；解析/非法数值是 ERROR，不因 !=/negate 放行。`ICON_UNMAPPED`、`SERVER_PAUSE_IGNORED` 为已知警告。元数据、服务状态和业务交易码不是统一 HTTP 状态的一一对应，不能只看200就当 YAML 已同步。

## 相关章节

[排错步骤](../05-operations/troubleshooting.md) · [精确 API](../../../WEB_API.md)

---

[上一章：06.4 变量与数值表达式参考](variables.md) · [下一章：06.6 术语表与资料权威顺序](glossary.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
