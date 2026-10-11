# 待答注释批量处理交接

> 历史记录说明：本文中的 `master-understanding` 是 Lessplain 的旧项目名；当时的路径、安装包和验证记录保留原样。

## 已完成

- 默认范围为当前理解图及其后代追问，不混入其他根图；只返回待答节点注释，不扩大到整个项目。
- `get_understanding_context` 新增 `capabilities.pendingNoteBatch=true` 与 `pendingBatch`。每项包含原问题、questionId 和独立固定 context，records 提供来源记录的当前版本；队列与记录来自同一数据库读取快照。
- Agent 保存初始 scopeRecordId，逐项核对源码并提交有效回答图；用新增可选参数 pendingScopeRecordId 固定最终复查范围，避免保存回答后画布切换影响队列。该参数与 contextToken 不能同时指定。
- 回答图、版本及请求去重保存成功后，在同一事务中删除对应独立 questions 行。失败或原问题已修改时不删除。不额外保存已回答注释历史，回答图仍保留原问题、questionId、固定来源与归属。
- 追问导航和同请求重试所需的问题信息从回答图恢复，保持原记录与问题身份；不全局清除以前版本遗留的已回答行。
- 删除“选择待答/已选为待答”及选择状态；注释查看、编辑、删除与添加入口保留。工程技能按整批快照工作，期间新加注释留到下一批。

工具名与 schemaVersion=3 保持；新增的是批量返回能力和可选范围参数。插件仍不自动启动 Agent，也不在此开发任务中回答或删除用户的真实待答注释。

## 已验证

针对性存储、核心及追问归属回归通过。新增五项回归覆盖多层队列、范围隔离、成功后的物理删除、重启读取、原问题修改与失败保留、同请求重试、追问导航及根图删除；序列化回归核对实际返回字段和固定范围复查。

完整 test：140 项，失败 0、错误 0、跳过 0。工程技能 quick_validate.py 通过，git diff --check 通过；生产与测试 Kotlin 文件未超过工程行数上限。

buildPlugin 与 verifyPlugin --offline 通过，IDEA 2026.1.5 和 2026.2.3 均判定 Compatible。Verifier 仍报告既有 deprecated/experimental API 使用，没有兼容性错误。

安装包：build/distributions/master-understanding-1.0.0-SNAPSHOT.zip，23,032,654 字节。

SHA-256：BE53C8EC78C67C575752EE2A95D804437D5AFEA299304085BEB14D7E189B133E。

使用内置 IDEA MCP 检查改动文件与已移除选择符号，未使用 Index MCP。新 payload、历史读取 helper 和 payload 测试的诊断返回 items=[]；其他存储文件仍出现运行时 SQLite 表无法解析的提示，实际数据库回归通过。诊断返回没有逐文件 analyzed/timedOut 证明，不能称为所有文件的完整分析。

未启动 IDE、操作桌面、截图或提交。保留此前未提交改动；本轮没有布局与路由变化，无需重跑性能基准。

## 需要用户验收

安装并重新加载新版后检查 capabilities.pendingNoteBatch=true；MCP 重连不能加载新的插件代码。验证同一图及多层追问中的注释一起返回，各项回答后气泡消失且追问入口仍可导航；中途修改的问题和保存失败的项继续保留。当前开发 IDE 的旧插件未尝试重新加载，因此尚未做真实界面批量验收。
