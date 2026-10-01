---
name: understanding-frames
description: 为 Master Understanding 插件组织有源码证据的理解图、回答节点追问或修订已有图。适用于用户要求在插件中呈现代码理解、沿图节点继续提问或更新旧理解；普通开发任务与单个独立事实答疑不因此创建图。
---

# Understanding Frames

Agent 负责分析真实源码并生成理解图，插件负责保存与呈现。只使用当前 IDE 实际提供的 `get_understanding_context` 和 `present_understanding`，可通过 IDEA MCP 的 router 调用；确认目标项目并传入正确的 `projectPath`。工具不可用时说明能完成的分析，不伪造保存成功，不为连接工具自行启动或重启 IDE。实际参数以在线 schema 为准；产品方向见 [主设计文档](../../docs/code-understanding-design-v0.5.md)。

## 决定如何回答

读实际源码及必要的调用、框架证据后再解释。只在答案是一个独立事实，无需解释过程、值的来源或变化、输入示例、分支或隐含步骤时，在 Agent 对话中用一两句话直接回答。不能按问题字数、代码行数或预计回答长度判断简单。用户要求保存到插件或回答节点追问时，即使答案简短，也必须提交有效图，不提交纯文字回答。

图优先表达具体输入、动作、输出及值的变化。节点与关系标签使用短语，不把长解释塞进节点；文字只补图无法表达的必要约束。示例标明是假设输入，不能称为真实执行。显示标题保持简短，约 20 个汉字，原始问题另行保留。

## 固定来源与回答追问

- 请求带有 `contextToken` 时，沿用该 token 读取来源。未带 token 且用户说“这里”“这一行”或沿已有节点追问时，先读取当前插件上下文；之后 UI 选择改变也不改绑问题来源。
- `get_understanding_context` 返回 `context`、来源 `record`、相关 `questions` 和可能为空的 `pendingQuestionId`。来源 token 在 `context.token`；`pendingQuestionId` 对应 `questions` 中的待答问题。读取原问题文本与固定来源，提交时把该 ID 传给 `questionId`，问题原文不能改写。
- 多条待答注释而没有明确目标时，请用户在节点注释气泡中选中目标，不猜最新一条。已有 `questionId` 的请求，使用该问题的原文和 token；与 UI 当前选择无关。复制载荷只是在交接问题，不代表已发送给 Agent。
- 节点追问必须使用 `placement=child`，归属固定来源记录。同一节点的追问图由插件放在共享画布的不同分组，不生成分组坐标。
- 创建其他新图时，有来源记录默认用 `child`；确实属于同级才用 `sibling`。独立问题显式用 `root`；指定其他有效父记录时用 `explicit` 和 `parentId`。工具报告来源过期或问题已修改时重新读取核对，不猜 ID 或偷偷省略来源。

## 组织可定位的图

每个节点必须能定位到自身对应的源码，优先提供直接 `anchor`。通过 `evidenceIds` 关联时，核对图中实际匹配的证据；当前导航在 `graph.evidence` 中取第一条匹配，而不是按 `evidenceIds` 的排列取第一条。不要让不同位置的证据匹配同一个节点，也不要把流程级证据复制给所有节点；确实对应同一段源码时才可共享锚点。

锚点使用项目相对 `path`、实际限定 `symbol`、原样 `text`、文件内容的 SHA-256 `version` 和片段首行 `lineHint`。版本可带 `sha256:` 前缀，不能使用 Git 提交号或片段哈希。以实际读取的当前源码快照为准，包括能取得的未保存内容；仅有磁盘内容时不能声称已经核对未保存文档。

`text` 覆盖步骤需要关注的完整、连续代码块，保留换行与缩进。讲整个函数时包含函数体和结束括号；讲分支或循环时包含对应逻辑块。不同职责分别选片段，不让每个节点都高亮整函数。跨越不相邻代码时拆分节点或证据，不夹带无关代码，不用省略号或改写代码代替实际子串。查不到或有多个匹配时保留不确定性，不用近似行号伪造定位。

框架隐含输入、输出或动作没有可核对的源码位置时，用简短标题、连线标签或 Agent 对话说明，不创建无源码的示例节点。图节点、关系和证据使用稳定 ID；边注明实际关系，控制流分支提供条件及分析覆盖范围。多条依赖关系不是互斥分支，静态选择也不代表已经执行。节点布局交给插件，不提交坐标。

## 保存与修订

- `present_understanding` 使用 `schemaVersion=1`，`graph` 是包含 `nodes`、`edges`、`evidence` 的 JSON 字符串，至少包含实际图节点。边的 `relation` 使用 `control_flow`、`call`、`data_flow`、`dependency` 或 `structure`。
- 每次操作使用唯一 `requestId`。同内容重试复用原 ID 与全部参数，内容或版本变化则换新 ID；不同内容不能占用已有 ID。
- 修订原图仍用 `present_understanding`，传 `placement=revise`、`reviseRecordId`、原问题和完整图。确认读到的 `record.id` 是修订目标，`expectedRevision` 取其当前 `record.revision`，不能使用来源快照里的旧 `context.recordRevision`。修订参数中的 `contextToken`、`questionId`、`parentId`、`displayTitle` 留空。
- 修订须保留节点 ID、边 ID、两者顺序以及每条边的起点、终点、关系类型。记录身份、追问、标题和浏览状态沿用；新问题创建新图，不借修订覆盖。版本冲突后重新读取记录并核对源码，不能只修改版本号重试旧内容。
- 保存前重读并逐节点核对 `path`、`text`、`lineHint`、版本及证据引用。位置仍能找到不证明解释仍有效，相关条件与依赖也要核对。
- 保存后核对返回的 `recordId`、`revision`、`parentId`、`questionId` 与本次归属一致。当前上下文工具没有任意记录 ID 查询参数，不承诺一定能读回新图；用户已经打开目标图时可读取其当前上下文进行检查，但不能据此改绑原问题。

## 当前能力边界

项目级关系图、Project Tree 注释、真实 Trace、完整 Diff 协议与自动任务交接尚未接入，不把规划中的字段提交给现有工具。需要运行事实时按用户请求和项目调试规则收集真实证据，但当前插件不能保存或回放 Trace 事件；假设示例不能冒充 Debugger 观测。当前交接仍由用户把问题载荷交给自己的 Agent。
