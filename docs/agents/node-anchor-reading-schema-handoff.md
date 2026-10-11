# 节点锚点、全部关系阅读与 schema 1.4

> 历史记录说明：本文中的 `master-understanding` 是 Lessplain 的旧项目名；当时的路径、安装包和验证记录保留原样。

日期：2026-10-10。与同期 UI 改造一并审查；不修改或回答用户的待答节点注释。合并前审查修复见 [Islands UI 交接](../islands-ui-refresh.md)。

## 已完成

- 源码定位核对符号时，从匹配片段的首个非空白字符取得 PSI 名称，不再从方法声明前的缩进取得类名称。定位和高亮仍保留完整片段的原始起点。
- 文件哈希不一致但片段唯一、符号成立时允许重新定位，证据状态仍为 UNKNOWN。重复片段仍为 AMBIGUOUS；其他函数中的匹配仍拒绝，不能以行号猜测替代身份。
- 图表与编辑器源码提示统一支持 control_flow、call、data_flow、structure、dependency 五种关系的前后阅读、候选选择及路径保存。start/end 角色不禁用已有连线；来源记录范围和循环限制继续生效。
- 分支图标单独只计入 control_flow、call、data_flow 的不同后继，结构和依赖关系不因允许阅读而变成流程分支。边类型不改写。
- 公开 schemaVersion 统一为字符串 `"1.4"`，上下文、MCP 入参、复制载荷一致。采用版本标识而非小数，后续 `"1.10"` 不会被截成 `"1.1"`；兼容能力由 capabilities 表达，不因每次功能更新升主版本。
- 兼容旧编号字符串 `"3"`、`"4"`，保留无分组限制及原来的整数请求哈希表示。`"1.4"` 与旧 4 是同一功能协议的编号调整，重试不冲突。已保存记录无需迁移，SQLite 版本不变。
- README、主设计及配套 Agent skill 同步阅读规则、版本规则和工具计算文件哈希的要求。

## 已验证

- JDK 21 下针对性导航、定位、协议测试通过。
- 全量 `test --offline`：278 项，0 失败、0 错误、0 跳过。
- `buildPlugin --offline` 通过，安装包为 `build/distributions/master-understanding-1.0.0-SNAPSHOT.zip`。
- `verifyPlugin --offline` 通过：IDEA 2026.1.5、2026.2.3 均为 Compatible。保留已有的废弃与实验 API 提示；本轮不引入新的平台 API。
- skill-creator 的 `quick_validate.py` 以 UTF-8 执行通过；`git diff --check` 通过，改动 Kotlin 文件符合行数限制。
- 安装包 SHA-256：`0A4B029AACC05B5A8645DB20287834704DDAF652FE8AEDF325A46C0CC346B572`。

## 待用户验收

安装新版并重新加载插件及 MCP 工具元数据后，检查：

1. 原讲解图中 n7 能跳到所属方法，并显示源码提示。当前哈希不同的事实仍保留，重新定位成功不代表原解释已重新核实。
2. n1 下一步进入 n2；n2 即使有 start 角色，上一步仍能回到 n1。结构及依赖候选与路径在图表和源码提示中一致。
3. 上下文返回 `"schemaVersion":"1.4"`；Agent 按新的 string 类型提交图。旧整数形态的 MCP 调用须按新工具元数据改为字符串，不承诺旧工具 schema 缓存继续有效。
4. 隐藏工具窗口后，编辑器需要选路线时仍显示 Understanding 工具窗口，沿用此前修复的行为。

自动化未启动 IDE、未进行桌面操作或截图；实际 PSI 和交互效果由新版安装验收确认。用户的三条问题注释继续保留供验收。
