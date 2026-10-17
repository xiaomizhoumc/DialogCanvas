# 03.2 JSON、YAML、同步与版本

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)

> 适用：DialogCanvas 1.0.0，Paper 26.1～26.3 系列（构建范围见最终验收）；核对日期：2026-10-16。

**前置知识：** YAML 基础；分清插件目录与 data-directory。

## 哪份文件是正式数据

正式依据为 `data/menus/<id>.json`；服主编辑 `menus/<id>.yml`；两份都是同一 MenuDefinition 根文档。`config.yml` 只放插件设置。文件名与文档 id 必须相同；浏览器上传文件名仅用于显示，ID 取内容。

`schemaVersion: 1` 是格式版本；`metadata.version` 是服务器管理的菜单版本；HTTP revision 是不透明并发令牌。revision、Cookie、同步状态、编辑器选中项、内部 editor ID、TipTap JSON 不写进菜单 YAML。metadata 不应手动修改。

## 手改流程

1. 关闭或暂停该菜单网页编辑，备份原 YAML。
2. 修改 `menus/wiki_01_notice.yml`，保留 ID 与格式版本。
3. 控制台 `dcmenu reload wiki_01_notice`。校验成功才提交 JSON、注册表，再规范化 YAML 与同步基线。
4. 网页“重新读取”，核对正式内容；游戏重新打开看效果。

失败保留上一份可用菜单，并报告具体字段。没 reload 不会生效；没有文件自动监听。`dcmenu reload` 无 ID 逐项导入、允许部分成功，不是多文件原子事务；引用目标要先存在。

## 双向冲突与中断

同步比较内容摘要，不靠文件修改时间。网页保存前发现手改未导入会拒绝覆盖；导入前发现双方均改变也拒绝自动选择。启动以 JSON 加载，保留待导入 YAML，不自动覆盖；基线缺失不得猜测重建。

正常保存顺序：校验快照 → JSON 原子写 → 注册表发布 → YAML 导出与同步状态。正式提交失败沿用补偿/停用保护；YAML 后失败返回部分成功。中断后仅在文件字节与持久化导出意图一致时自动补齐，否则保留并提示核查。

不要同时网页保存和手改同菜单，不运行中修改正式 JSON/基线。已有旧版正式 YAML 的一次性迁移需备份、同名检查和全集引用校验；不是复制任意第三方配置就自动转换。具体旧目录与回执以专题为准。

## 相关章节

[手动 YAML 权威专题](../../../YAML_EDITING.md) · [同步排错](../05-operations/troubleshooting.md)

---

[上一章：03.1 YAML 基础与常见语法错误](basics.md) · [下一章：03.3 从最小菜单到完整菜单](tutorial.md)

[返回 Wiki 首页](../README.md) · [项目入口](../../../README.md)
