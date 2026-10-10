# 协议细则

按需查阅。字段和类型以在线工具 schema 为准。

## 版本与能力

- 上下文返回的 `schemaVersion` 当前是字符串 `"1.4"`，原样提交，不转成小数。复制载荷里的 `protocolVersion` 与它相同。
- 旧编号 `"4"` 和不带 `clusters` 的 `"3"` 仍可提交。旧插件返回整数版本时，按其在线 schema 的类型与能力提交，不绕过工具直接写数据库。
- 字段是否可用看 `capabilities`：`nodeSemantics` 允许提交 `endOutcome`，`pendingNoteBatch` 提供批量注释，`rootClusters` 允许根图分组。没有声明的能力不提交对应字段；缺少所需能力时说明需要加载新版插件，不猜测未返回的数据。

## get_understanding_context

返回 `schemaVersion`、`context`（`token`、`recordId`、`nodeId`、`anchor`、`staticPath` 等）、来源 `record`、相关 `questions`、`pendingBatch` 和 `capabilities`。

- `contextToken` 与 `pendingScopeRecordId` 不能同时指定。
- `context.staticPath` 是用户当时的阅读路径，可能从中间节点开始；它不证明完整入口、实际执行或业务条件成立。不要为补全路径猜入口，也不提交 AI 选路字段。

## present_understanding

| 参数 | 说明 |
|---|---|
| `schemaVersion` | 上下文返回的值 |
| `requestId` | 每份新内容一个新 ID。同内容重试复用原 ID 和全部参数；内容或版本变化就换 ID；不同内容不能占用已有 ID |
| `question` | 原问题。带 `questionId` 时必须与保存的原文一致 |
| `graph` | JSON 字符串：`nodes`、`edges`、`evidence`，根图可选 `clusters`；至少一个实际节点 |
| `placement` | `root`、`child`、`sibling`、`explicit` 或 `revise` |
| `displayTitle` | 可选短标题，约 20 字；修订时留空 |
| `contextToken`、`questionId` | 固定来源与待答问题；两者必须属于同一来源；修订时留空 |
| `parentId` | 只用于 `explicit` |
| `reviseRecordId`、`expectedRevision` | 只用于 `revise` |

返回 `recordId`、`revision`、`parentId`、`questionId`。保存后核对它们与本次归属一致。

### 归属

- 节点追问和待答注释一律用 `child`，归属固定来源记录。带 `questionId` 时只能用 `child`。
- 其他新图按理解关系选归属，不按提问先后：追问来源记录的内容用 `child`；与来源记录同级的问题用 `sibling`；与当前图无关的独立问题用 `root`；挂到其他已知记录下用 `explicit` 加 `parentId`。没有来源记录时只能用 `root` 或 `explicit`。
- 工具报告来源过期、问题已修改或已取消时，重新读取核对，不猜 ID，也不省略来源来绕过校验。

## graph 字段

- 节点：`id`、`label`、`anchor`，可选 `flowRoles`、`endOutcome`、`evidenceIds`。`kind` 省略即可（默认 `step`）；`placeholder` 和 `question` 是插件保留值。
- 边：`id`、`from`、`to`、`relation`（`control_flow`、`call`、`data_flow`、`dependency`、`structure`），可选 `condition`、`coverage`、`evidenceIds`。`condition` 只能用在 `control_flow`；调用与数据关系的适用条件写进 `coverage`。多条依赖边不是互斥分支。只分析了部分分支时，在 `coverage` 注明范围，不为凑分支虚构路径。
- 节点、边、证据的 ID 在图内唯一且稳定。不提交坐标、`decision` 或其他布局字段；分支图标由插件按可浏览后继计算。
- `evidence` 可选。节点没有 `anchor` 时，插件按 `graph.evidence` 的排列顺序取第一条被该节点 `evidenceIds` 引用的证据来定位，而不是按 `evidenceIds` 的顺序。不要让不同位置的证据匹配同一个节点，也不要把流程级证据复制给所有节点。

### flowRoles 与 endOutcome

- `flowRoles` 取 `["start"]`、`["end"]` 或 `["start", "end"]`，按当前解释范围标注业务起点和结束点，可以有多个。不要根据入度、出度或 `kind` 推断角色。
- `endOutcome` 只在 `capabilities.nodeSemantics=true` 时提交，且节点必须带 `end`。业务成功写 `success`；校验拒绝、权限不足、未恢复的异常写 `failure`。捕获后恢复并继续执行的异常不算失败。同一节点既可能成功也可能失败，或证据不足时，省略该字段。正常跳过算不算完成职责，要按解释范围判断，不能只看 `return` 或方法名。

### 根图分组（clusters）

`capabilities.rootClusters=true` 时，根图可提交单层 `clusters=[{id,title,nodeIds}]`。每组对应源码中的一项职责、一个触发入口或一个处理阶段；成员完整覆盖全部节点且互不重叠；不嵌套、不带坐标；跨组的真实关系照常保留。先检查原分组的连线，再考虑语义成立的合并，不要为了少画线把无关业务并成一组。追问子图不提交分组。

## 待答注释批处理

1. 记下 `pendingBatch.scopeRecordId` 和 `items` 快照。每项有 `questionId`、`question` 原文和固定 `context`：来源节点是该项的 `context.nodeId`，token 是该项的 `context.token`，所属记录在 `pendingBatch.records` 里按 `context.recordId` 匹配。不要把顶层的当前节点、其他项的 token 或当前界面选择套用到整批。
2. 逐项核对源码后提交回答图，使用 `child` 以及各自的 `questionId`、原问题和 token。只有用户明确只问某一条时才缩小范围。
3. 插件在保存回答的同一事务里删除对应注释。失败、取消或证据不足的项保持原样，不要用删除或清空冒充已回答。未保存的草稿不在队列里，不要猜它的内容。
4. 最后用 `get_understanding_context(pendingScopeRecordId=最初的 scopeRecordId)` 复查，不依赖回答过程中自动切换到的画布。没完成的项逐条说明原因。期间新加的注释留到下一批，不反复读取、不自动扩大任务。

## 修订（placement=revise）

- `reviseRecordId` 用读到的 `record.id`，`expectedRevision` 用该记录当前的 `record.revision`，不要用来源快照里的 `context.recordRevision`。`question` 必须与原问题一致。
- 节点 ID、边 ID 及两者顺序必须保留，每条边的起点、终点和关系类型也不能变；可以更新 label、锚点、条件、角色和分组。记录身份、追问、标题和浏览状态都沿用。新问题创建新图，不借修订覆盖。
- 版本冲突时重新读取记录、重新核对源码，不能只改版本号重试旧内容。
- `clusters` 省略或为 `null` 时保留现有分组，`[]` 清空，非空数组整组替换且必须与提交的节点一致。保留、清空、替换的语义变了就换新的 `requestId`。
