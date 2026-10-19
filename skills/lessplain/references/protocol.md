# 实验协议

插件暴露三个工具，参数以当前 IDE 公布的 schema 为准。

| 工具 | 输入 | 用途 |
|---|---|---|
| lessplain_context | ref 可省略 | 新页面，或读取固定结果/问题/源码来源 |
| lessplain_present | context, view | 创建与完整替换共用一个提交 |
| lessplain_finish_feedback | refs | 只完成已经处理的反馈 |

view 是对象，不是 JSON 字符串。spec 的可用组件和参数由 context 返回的 catalog 指定。工具目标、原问题身份、归属、版本和重试由 context 固定。

sources 是 ID 到源码引用的映射，每项包含 path、text 和可选 symbol。path 为项目相对路径；text 是实际 IDE 文档中的唯一片段，不是解释文字。提交时插件计算版本和位置。多处同文时提供更具体片段或 symbol。源文件之后变化会影响定位和证据状态，位置能找回不等于解释仍然正确。

原生编辑器选区来源由插件冻结，可在 context 的已解析来源中包含 selectionRange：{startOffset,endOffset}，使用 UTF-16 半开区间并随问题/回答快照保留。它不是 Agent 的 sources 输入字段；不要生成或修改范围。保存校验文档版本、范围及原文；失配时重新选择，不能用全文搜索猜位置。

demos 是 ID 到 {html} 的映射。HTML 保存到结果中，使用内联 CSS/JS，不引用磁盘路径或网络资源；展示时运行在无同源权限的 sandbox iframe。

codeSamples 是 ID 到 {title,language,code} 的映射，保存独立教学代码。每份最多 100000 字符，最多 20 份、总计 500000 字符；保存时统一换行为 LF。language 使用 kotlin、java、javascript 等语言名。它不指向项目文件，也不表示已经运行。CodeSample 在原生只读编辑器打开；需要修改或运行时，用户可通过编辑器右键“Lessplain: 创建可编辑练习副本”创建 Scratch，运行能力取决于 IDE 语言支持和依赖。

样例选区提问使用 entry=sampleSelection，origin.sample 冻结资源 ID、样例、版本及 UTF-16 选区；由插件生成，Agent 不伪造或改写。旧问题保留原快照，旧选区在样例变更后不能作为新问题保存。

## spec

根结构为 {root, elements, state?}。elements 是 ID 到 {type, props, children?, slots?, on?, visible?, repeat?} 的映射。ID 保持稳定，children 顺序就是阅读顺序。图的节点、边也保持稳定 ID。组件树禁止循环、多重父节点和不可达组件。

可用标准组件包含 Stack、Grid、Card、Heading、Text、Badge、Separator、Tabs、Accordion、Collapsible、Button、Input、Select、Slider、Switch。参数使用返回的 catalog；不要猜第三方版本的字段。共享状态读取用 {$state:"/path"}，双向绑定用 {$bindState:"/path"}，按 json-render 官方 JSON Pointer 语法。

自定义组件：

| 组件 | 参数 |
|---|---|
| Graph | nodes、组内 edges；参数以 catalog 为准 |
| Board | children 引用多个 Cluster；props.edges 可选跨组关系；无坐标、无 slots |
| Cluster | title；slots.diagram 至多一张 Graph，slots.companion 为普通组件或容器 |
| SourceLink | source, label |
| CodeExcerpt | source, label 可选 |
| CodeSample | sample, label 可选, startLine/endLine 可选，一基闭区间 |
| JsonView | value, label 可选，format=json/text 可选；默认在原生只读编辑器打开 JSON，text 只用于原始字符串 |
| Demo | demo, title, height 可选 |

Graph 节点是 {id,label,detail?,source?,sample?,roles?,outcome?}；roles 为 start/end，outcome 为 success/failure。边是 {id,source,target,kind,label?,summary?,evidence?,inferred?}。边 source/target 是节点 ID；节点 source 与 evidence 是 sources 中的 ID。来源/示例 ID 使用固定字面量。节点和关系结构由 Agent 提交，坐标、测量与 ELK sections 由插件前端计算。

Card、Badge 可设置 tone=neutral/info/success/warning/danger；省略时沿用既有样式，显式 tone 控制语义色。CodeExcerpt 默认打开保存的源码快照，另有定位当前源码入口。JsonView 的 value 可绑定当前状态，打开后冻结，不随之后的操作变化；少量结果使用 Text 直接呈现。

组件代码选区使用 entry=componentSelection，origin.componentSnapshot 保存组件身份、内容版本、全文和 UTF-16 选区，origin.state 保留打开时状态。该来源由插件签发，Agent 直接读取，不改写或伪造成项目源码及静态 codeSamples。原始文本保存在 originalText，原生编辑器使用规范化换行后的正文定位。页面删除或组件内容改版使旧入口失效，已保存的问题仍保留冻结快照。

节点 sample 为 {id,startLine?,endLine?}，id 引用 codeSamples。省略 startLine 使用第 1 行，省略 endLine 只定位起始行；范围必须在资源内。同一节点只绑定 source 或 sample 之一。CodeSample 组件的资源 ID 与行号使用固定字面量。openSample action 参数为同一 target 对象。

label 只保留必要的短分支条件，使用单行轻量文字；参数、数据和重复步骤说明放 summary，通过悬停或键盘聚焦按需查看。没有 label 时不自动显示关系类型。

Graph 可设置 selectionPath 为固定 JSON Pointer，用于和组件双向共享选中节点。不同图的选择路径必须独立，不能相同或互为父子路径。节点只默认显示两行以内的 label；detail 不作为常驻说明区。完整源码优先通过 source 定位到原生编辑器，CodeExcerpt 仅用于用户确实需要的字段或代码对照。

Cluster 不使用 children。diagram 必须直接引用 Graph；companion 中不能嵌套 Graph、Board 或 Cluster。标题在组内显示。单组可直接作为 root；多组使用 Board.children。整个页面只有一个画布，分组、节点和组件一起平移与缩放；Graph 是逻辑节点集合，不是独立图窗。

分组单位是用户的问题。需要比较的对象保持同时可见，职责区别可用无边源码节点表达；Tabs、折叠和状态显隐按当前阅读任务选择。CodeExcerpt 只用于有价值的源码快照对照，保留各文件的独立真实来源。数据示例只在有助理解时添加，入口使用短用途名，模拟值及限制由附近文字说明。代码与 JSON 继续在 IDEA 编辑器标签打开。

含图页面必须以 Board、Cluster 或 Graph 为根。Stack、Grid、Card 等普通容器放在 companion 中，不能包裹图；Graph 本身不接收 children 或 slots。纯组件页面仍可使用普通容器根。插件对已存历史提供显示投影，但新提交必须使用上述结构。示例：

```json
{
  "root": "cache",
  "state": {"selection": "read"},
  "elements": {
    "cache": {"type": "Cluster", "props": {"title": "缓存读取"}, "slots": {"diagram": ["flow"], "companion": ["value"]}},
    "flow": {"type": "Graph", "props": {"selectionPath": "/selection", "nodes": [{"id": "read", "label": "读取缓存"}], "edges": []}},
    "value": {"type": "JsonView", "props": {"label": "当前节点", "value": {"$state": "/selection"}}}
  }
}
```

组内关系仍放在 Graph.edges，端点为该 Graph 的节点 ID。跨组关系放在 Board.props.edges，source/target 为 `{elementId,nodeId}`，elementId 指此 Board 内的 Graph 元素；其他字段与组内边一致。示例：

```json
{"id":"load","source":{"elementId":"entry-graph","nodeId":"request"},"target":{"elementId":"cache-graph","nodeId":"read"},"kind":"call","evidence":["actual-call"]}
```

节点 ID 只须在各 Graph 内唯一；跨组边 ID 在 Board 内唯一。不能引用其他 Board 的图或不存在的节点。真实有关联时才添加跨组边；同组关系不要改写成跨组结构，推断关系仍须提供源码依据。

## Demo 通信

iframe 发出：

parent.postMessage({channel:"lessplain-demo",method:"setState",params:{path:"/choice",value:"hit"}},"*")

允许的 method：

- setState：params.path 为 JSON Pointer；value 为 JSON 数据。
- openSource：params.source 是此页面保存的源码 ID。
- openSample：params 为 {id,startLine?,endLine?}，引用已保存教学代码。
- ask：params.text 是要保存的示例追问。
- resize：params.height 在 120 到 1200 之间。

父页面会给 iframe 发送 {channel:"lessplain-demo",method:"state",params:{state,theme}}。theme 提供 --demo- 前缀的主题变量，包括 --demo-background、--demo-foreground 和 --demo-color-scheme；宿主自动应用到隔离文档根元素，内部页面背景和控件配色随主题切换。监听 message 后读取 params.state，以该状态驱动演示。只有页面的 iframe 实例发来的消息才被处理。示例不能执行 IDE 命令、读取项目文件或替用户调用 Agent。

## 重试与更新

提交同一 context 和同一内容返回同一 ref/revision。已经用该 context 保存后，提交不同内容会失败；先使用返回 ref 重新读取。新 context 若遇到版本冲突、问题删除、问题编辑或已由另一个提交回答，则保留现状并要求重新读取。验证失败不保存半成品，也不完成待答问题。

回答记录可包含 answeredNote，它保存当时的版本化问题及冻结来源。成功回答与原待答行删除原子完成；历史 note: 从回答快照重建并指向该回答，继续读取可修订原回答。手动删除待答行会硬删除其未提交 context，旧引用不能再回答。浏览器内部操作名为 deleteNote；取消编辑只丢弃本次编辑，不删除问题。
