# 首版实施草案

产品范围以 [主设计文档](code-understanding-design-v0.5.md) 为准。以下是开发建议，不是完成状态；先验证关键接口，再固定实现。

选型记录：2026-09-27 已放弃原生 Diagram，当前图由插件的 Swing 组件绘制。下文“优先验证 Diagrams”的内容保留为当时的评估过程；现状和待复验项见根目录 [README](../README.md)。

## 1. 兼容与图形实现

- 最低目标 2026.1，基线为 261；后续版本持续验证，不把版本号范围当作永久兼容保证。
- 长期覆盖基于 IntelliJ Platform 的 JetBrains IDE。首批正式验收建议为 IntelliJ IDEA、Kotlin／Java；其他 IDE 分别标注已验证、未验证和缺少的能力。
- 历史、问答、通用图模型尽量只依赖平台能力。语言解析和调试作为适配层；可显示 Agent 的结果不等于已支持该语言的语义校验或真实 Trace。
- 用 Plugin Verifier 检查目标产品，再做启动和关键交互验证。通过二进制检查不能替代功能测试，未验证产品不宣传完整支持。

用户截图是原生“图表”中的类关系图，底部标有 Powered by yFiles。优先验证通过 Diagrams 插件接口接入自定义节点和关系，复用导航、缩放、布局和原生菜单；不把类图的字段／方法列表直接当成理解节点的最终样式。

验证项：2026.1 的 API 与依赖是否可用、工具窗口和编辑器能否共用图视图、自定义节点能否承载分支与命中操作、完整项目规模下是否可用。JetBrains 的历史答复确认了 com.intellij.diagram 依赖，但不能据此认定所有 2026.1 产品都支持；具体产品、许可使用范围和 API 稳定性仍需核实，不直接打包 IDE 内的 yFiles 库。

若原生 Diagram 与跨 IDE 目标冲突，先给出实际限制再决定替代实现。当前不承诺维护两套渲染器，也不预先改成网页。数据模型保持独立，避免图引擎反过来决定历史格式。

2026-09-27 实施记录：原生 Diagram 的初始化触发内部 API 门禁。GraphChart v2 的公开接口未能提供最终连线路径及流动相位控制，直接调用布局器抛出 `NotImplementedError`。用户确认后，当前产品图改为插件自身的 Swing 绘制组件，静态线条与移动虚线共用一条路径；Jewel 承载记录和历史界面。节点提问目前复制固定来源，由用户交给 Agent。构建、测试和 Plugin Verifier 曾通过；最新图层状态交互仍待开发 IDE 复验。

MCP 建议先做薄适配层接入现有 IDEA MCP 能力。开工验证其工具注册接口、产品覆盖和分发依赖后，再确定具体依赖；不先自建 Agent、搜索或调试系统。

Agent handoff 也做成薄适配层，不依赖 AI Chat。首版只要求“复制问题载荷 → 用户在任意 Agent 宿主提交 → Agent 经 MCP 回写”可用；若未来 Air、IDE 或其他宿主提供稳定的任务提交 API，再增加对应 adapter。核心协议只认识 `questionId`、`contextToken` 和返回结果，不认识 AI Chat 会话、Air session 或某个供应商的私有状态。

依据：[平台兼容性](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)、[2026.1／261](https://platform.jetbrains.com/t/2026-1-is-coming-time-to-check-your-plugin-compatibility/3865)、[Diagram 依赖答复](https://intellij-support.jetbrains.com/hc/en-us/community/posts/19055180456210-What-dependencies-to-use-to-import-com-intellij-diagram-in-my-plugin)。

## 2. 工具建议

以下名称和参数是首版协议草案，不是已暴露工具。每次调用绑定项目；所有写入带 schemaVersion 和 requestId，同一请求重试返回原结果，同 ID 不同内容报冲突。更新携带 expectedRevision，不允许迟到结果覆盖新版本。

| 工具 | 关键输入与返回 |
| --- | --- |
| get_understanding_context | 输入可选 contextToken；返回固定来源、能力列表及 token，省略时捕获当前选择 |
| get_understanding | 输入 recordId、可选 revision；返回已保存的问题、显示标题、图和证据引用 |
| list_understandings | 输入可选 query、parentId、分页 cursor；返回匹配的图记录与下一页位置 |
| present_understanding | 输入 questionId?、contextToken?、question、displayTitle?、placement、graph；创建图记录，返回 recordId、revision 和校验结果 |
| update_understanding | 输入 recordId、expectedRevision、displayTitle?、graph、evidence；写入新版本，保留旧版本 |
| update_runtime | 输入 recordId、graphRevision、traceId、sourceRevision、events、completeness；接收整份单调用记录 |
| set_tree_annotations | 输入 scope、expectedRevision、sourceRevision、entries；按范围替换说明，entries 含 path、roles[]、evidenceRefs |
| present_project_understanding | 输入 projectGraphId?、expectedRevision?、sourceRevision、coverage、nodes、edges、evidence；创建或更新全局图版本 |

placement 为 child／sibling／root／explicit；默认 child，explicit 携带 parentId。无来源或来源没有父级上下文时，要求明确选择 root 或有效父级，不按当前 UI 猜测。更新版本不重写原提问来源。

handoff payload 至少包含 `questionId`、`contextToken`、问题文本和协议版本。宿主 adapter 只能负责把 payload 交出去；不能改变来源、问题归属或返回协议，也不能靠驻留等待／长轮询维持 Agent 会话。

准备 handoff 时，插件先保存 questionId、问题和来源 token，再生成宿主无关的 payload。首版 payload 直接复制；以后 adapter 可以把同一 payload 交给支持的 Agent 宿主。Agent 返回时带回 questionId，不重复创建提问；尚未收到回答就显示“未收到回答”，不把“复制”或“已交接”误写成“已回答”。来源失效、跨项目或引用版本不存在时明确报错。

graph 保存稳定 nodeId／edgeId、关系类型、分支条件、锚点与证据引用，不接收坐标。项目图额外保存成员范围与 coverage 的已分析、未分析、排除范围；区域聚合不缩减全局覆盖。相同节点 ID 的历史值仍按图版本隔离。

TraceHit 为 {seq, nodeId, values, observationAnchor, executionContext?}。values 每项区分 value／not_observed／evaluation_failed，value 可以为 null。seq 严格递增且不代表并发因果；新采集使用新 traceId。相同 traceId 的完全相同重传幂等，不同内容报冲突，首版不实现分批追加。

## 3. 存储建议

建议用项目本地 SQLite，位置为 .idea/code-understanding/understanding.db，默认排除版本控制。事务覆盖内容、版本和索引更新，避免全量重写一个不断变大的 JSON 文件。后续渲染器或协议变化不改变存储归属。

| 数据 | 保存内容 |
| --- | --- |
| 项目与记录 | schemaVersion、projectId；recordId、parentId、origin、当前 revision |
| 问答 | questionId、answerId、原文、来源、所属记录、时间与状态；保留每次实际回答，网络重试去重 |
| 图与版本 | recordId／projectGraphId + revision；graph JSON、证据引用、coverage；旧版本不可被覆盖 |
| 源码证据 | 内容哈希、相对路径、语言与文本快照；提交／工作区／未保存内容的版本身份 |
| 上下文与浏览 | token 对应的固定来源；当前节点、静态路径、traceId + seq、图位置和目录显示偏好 |
| 运行记录 | traceId、图与源码版本、完整性；每条命中单独保存，值为 JSON |

token 与问题来源可跨重启恢复；获取当前上下文产生新快照，不改变旧 token。历史保存引用到的源码片段或必要文件快照，不复制整个仓库或外部 Agent transcript。

迁移前备份，失败保留原库；索引可重建，问答和图历史不能作为缓存清掉。大型运行值允许截断并标记，容量治理不能悄悄删除问题。存储失败时明确显示未保存，不返回成功。首版只允许一个 IDE 实例写入同一项目，第二个实例提示只读，避免无意覆盖。

## 4. 真实验收建议

| 样例 | 通过条件 |
| --- | --- |
| 接入与提问 | 外部 Agent 经真实 MCP 提交图；不依赖 AI Chat，复制 payload 后切换文件，回答仍挂回原来源；若验证直接 handoff，只替换宿主 adapter；重试不产生重复记录 |
| 条件分支与三次重试 | 可执行 Kotlin 小项目包含成功、提前返回、循环；静态选路能改选；真实运行保留 timeout／busy／ok 三次观测，回退不显示未来值 |
| 代码变化 | 插入行、移动函数、复制相同语句、改变被调用函数、保留未保存编辑；定位与证据状态分别正确，旧 Trace 不冒充新事实 |
| diff | 两个固定版本改变判断或调用顺序；图节点打开正确一侧的源码，删除节点可看旧代码，前后匹配不确定时不强行合并 |
| 完整项目图 | 一个可审阅的完整项目验证所有目标文件已覆盖或有排除原因；再用 mulehang-agent 完整目标源码范围验证跨模块聚合、展开、源码导航与局部更新，不能用单模块截图代替 |
| 保存与恢复 | 提问、简短回答、图和回放进度经重启仍可搜索和恢复；迁移失败不丢旧库；存储故障不谎报保存成功 |
| IDE 体验与兼容 | IJ 2026.1 与当前支持版本验证侧栏、编辑器图视图、主题、键盘、原生菜单；其他 IDE 按同一清单逐个记录，不用“能安装”代替支持 |

验收用独立测试项目或临时副本，不修改日常工作的产品代码。Trace 必须记录真实 Debugger 观测；合成大图只用于补充性能测试。全局图需记录节点／边规模、首次布局和交互耗时；正式容量目标在首次实测后确定。
