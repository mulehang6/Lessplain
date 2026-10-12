# 协议细则

字段和类型以工具自带的 schema 为准。本文件只记录 schema 无法说明的规则。

## 版本与能力

- `schemaVersion` 是字符串，例如 `"1.4"`。按上下文返回的值原样提交，不转成数字。
- `capabilities` 决定哪些字段可用。没有声明的能力，不提交对应字段。

| 能力                  | 为 true 时可用                          |
|-----------------------|-----------------------------------------|
| `nodeSemantics`       | 节点提交 `endOutcome`                   |
| `pendingNoteBatch`    | 使用待答注释批处理                      |
| `rootClusters`        | 根图提交 `clusters`                     |
| `anchorValidation`    | 保存前校验锚点，锚点错误会列出全部问题  |
| `edgeSummaries`       | 边提交自然语言短句 `summary`            |
| `inferredEdges`       | 边提交 `inferred: true`                 |
| `feedbackConsumption` | 用 `feedbackQuestionIds` 清理反馈类注释 |

缺少所需能力时，说明需要更新插件，不猜测未返回的数据。

## get_understanding_context

返回 `schemaVersion`、`context`（`token`、`recordId`、`nodeId`、`anchor`、`staticPath` 等）、来源 `record`、`questions`、`pendingBatch` 和 `capabilities`。

| 参数                   | 规则                                                                          |
|------------------------|-------------------------------------------------------------------------------|
| `contextToken`         | 固定来源。空值表示捕获当前来源                                                |
| `pendingScopeRecordId` | 读取复制文本末尾的范围标识，或复查时传回 `pendingBatch.scopeRecordId`。不能与 `contextToken` 同时指定 |
| `feedbackQuestionIds`  | JSON 字符串数组。只能包含本次读取范围内的注释 `questionId`，读取成功后删除    |

`context.staticPath` 是提问时的阅读路径，可能从中间节点开始。它不能证明入口、实际执行或业务条件成立。补全路径时不猜入口。

## present_understanding

| 参数                                 | 规则                                                                                                          |
|--------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `schemaVersion`                      | 按上下文返回的值提交                                                                                          |
| `requestId`                          | 每份新内容用一个新 ID。同一内容重试时，复用原 ID 和全部参数。内容或版本变了就换新 ID。不同内容不能占用已有 ID |
| `question`                           | 原问题。带 `questionId` 时，必须与保存的原文一致                                                              |
| `graph`                              | JSON 字符串，包含 `nodes`、`edges`、`evidence`，根图可选 `clusters`。至少一个实际节点                         |
| `placement`                          | `root`、`child`、`sibling`、`explicit` 或 `revise`                                                            |
| `displayTitle`                       | 可选，约 20 字。修订时留空                                                                                    |
| `contextToken`、`questionId`         | 固定来源与待答问题，两者必须属于同一来源。修订时留空                                                          |
| `parentId`                           | 仅用于 `explicit`                                                                                             |
| `reviseRecordId`、`expectedRevision` | 仅用于 `revise`                                                                                               |

保存后，返回的 `recordId`、`revision` 和 `resolvedAnchors` 用来核对。`resolvedAnchors` 列出每个锚点解析到的路径和行号。根图的一次实际调用没有返回 `parentId`，因此归属要结合 `placement` 判断，不要求每次都有这个字段。

### 归属

| 情况                   | placement                                                   |
|------------------------|-------------------------------------------------------------|
| 节点追问、待答注释     | `child`，归属固定来源记录。带 `questionId` 时只能用 `child` |
| 追问来源记录的内容     | `child`                                                     |
| 与来源记录同级的问题   | `sibling`                                                   |
| 与当前图无关的独立问题 | `root`                                                      |
| 挂到其他已知记录下     | `explicit`，并填 `parentId`                                 |

没有来源记录时，只能用 `root` 或 `explicit`。归属按理解关系选，不按提问先后选。

工具报告来源过期、问题已修改或已取消时，重新读取核对。不猜 ID，也不去掉来源来绕过校验。

## graph 字段

- 节点：`id`、`label`、`anchor`，可选 `flowRoles`、`endOutcome`、`evidenceIds`。`kind` 省略即可，默认是 `step`。`placeholder` 和 `question` 是插件保留值。
- 边：`id`、`from`、`to`、`relation`，`relation` 取 `control_flow`、`call`、`data_flow`、`dependency`、`structure` 之一。可选 `summary`、`condition`、`inferred`、`coverage`、`evidenceIds`。
  - `summary` 是一句自然语言关系说明，仅在 `edgeSummaries=true` 时提交。缺省、null 或空白时，悬浮显示中文关系类型和两端节点；不从 `coverage` 截取。悬浮宽度不超过 360 个逻辑像素，正文最多三行。
  - `coverage` 保留完整依据，右键边的“查看依据”同时展示条件和关联证据；打开详情不改变阅读路径。
  - `condition` 只用于 `control_flow`。调用和数据关系的适用条件写进 `coverage`。
  - `inferred: true` 必须同时有非空的 `coverage`，写明依据。
  - 多条 `dependency` 边不是互斥分支。
  - 只分析了部分分支时，在 `coverage` 注明范围。
- ID 在图内唯一且稳定。不提交坐标、`decision` 或其他布局字段。分支图标由插件按可浏览的后继计算。
- `evidence` 可选。节点没有 `anchor` 时，插件取 `graph.evidence` 中第一条被该节点 `evidenceIds` 引用的证据来定位，按 `graph.evidence` 的排列顺序，不按 `evidenceIds` 的顺序。
  - 不让不同位置的证据匹配同一个节点。
  - 不把流程级证据复制给所有节点。

### flowRoles 与 endOutcome

- `flowRoles` 取 `["start"]`、`["end"]` 或 `["start", "end"]`。按当前解释范围标注业务起点和终点，可以有多个。不根据入度、出度或 `kind` 推断角色。
- `endOutcome` 只在 `nodeSemantics=true` 时提交，且节点必须带 `end`。
  - 业务成功写 `success`。
  - 校验拒绝、权限不足、未恢复的异常写 `failure`。
  - 捕获后恢复并继续执行的异常不算失败。
  - 同一节点既可能成功也可能失败，或证据不足时，省略该字段。
- 正常跳过是否算完成职责，按解释范围判断，不只看 `return` 或方法名。

### 根图分组（clusters）

是否采用多 cluster，遵循 [多 cluster](../SKILL.md#多-cluster) 中的分组条件。`rootClusters=true` 只表示工具支持分组，不代表当前图适合分组。

`rootClusters=true` 且满足分组条件时，根图可提交单层 `clusters=[{id,title,nodeIds}]`。

- 每组对应同一问题下的一条独立流程或一项独立职责，标题说明组内内容。不同入口或处理阶段本身不是拆组依据。
- 成员完整覆盖全部节点，且互不重叠。
- 不嵌套，不带坐标。
- 提交前按主文的分组条件检查全部边，有跨组关系就合并相关组，保留真实关系。
- 追问子图不提交分组。

## 待答注释批处理

复制文本末尾的 `Lessplain 待答批次：<范围标识>` 使用真正的理解根记录 ID。首次调用 `get_understanding_context(pendingScopeRecordId=<范围标识>)` 读取根记录和全部追问图的最新待答注释，不按复制文本的编号列表筛选。复制后、首次读取前新增的注释也包含在内，已完成的注释不再返回。默认连接当前正确的 IDE 工程；范围不存在时报告无法读取，不尝试其他工程。

1. 记下 `pendingBatch.scopeRecordId` 和 `items` 快照。每项包含 `questionId`、原文 `question` 和固定的 `context`。
   - 来源节点是该项的 `context.nodeId`。
   - token 是该项的 `context.token`。
   - 编辑器选区提问的 `context.anchor` 包含点击“提问”时的代码快照（包括未保存内容），问题正文保持用户原话。优先回答选区，不把整个节点的源码当作选区。
   - 所属记录在 `pendingBatch.records` 中按 `context.recordId` 匹配。
   - 不把顶层的当前节点、其他项的 token 或当前界面选择套用到整批。
2. 逐项核对源码后，以 `child` 提交回答图，带上各自的 `questionId`、原问题和 token。只有用户明确指定某一条时，才只处理那一条。
3. 插件在保存回答的同一事务中删除对应注释。失败、取消或证据不足的项保持原样，不用删除或清空来冒充已回答。未保存的草稿不在队列中，不猜测其内容。
4. 最后用 `get_understanding_context(pendingScopeRecordId=<最初的 scopeRecordId>)` 复查。不依赖回答过程中自动切换的画布。仍未完成的项逐条说明原因。首次读取后、处理期间新增的注释留到下一批，不自动扩大本次任务。

## 修订（placement=revise）

- `reviseRecordId` 用读到的 `record.id`。`expectedRevision` 用该记录当前的 `record.revision`，不用来源快照里的 `context.recordRevision`。
- `question` 必须与原问题一致。
- 节点 ID、边 ID 及两者的顺序保持不变。每条边的起点、终点和关系类型不变。可以更新 label、锚点、条件、角色和分组。
- 记录身份、追问、标题和浏览状态沿用原值。新问题创建新图，不借修订覆盖旧图。
- 版本冲突时，重新读取记录，重新核对源码。不只改版本号重试旧内容。
- `clusters` 省略或为 `null` 时保留现有分组，为 `[]` 时清空，为非空数组时整组替换，且必须与提交的节点一致。保留、清空或替换的语义变了，就换新的 `requestId`。
