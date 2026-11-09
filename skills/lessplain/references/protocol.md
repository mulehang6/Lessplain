# 结构化 MCP 协议

共 15 个工具。以下为直接调用的参数对象。经 IDE 路由时按该路由的 schema 封送这些对象，目标工程填外层 projectPath。返回统一为 {ok:true,data:...} 或 {ok:false,error:{code,field,message,currentRevision?}}。

ID 为示意值，实际必须使用前次返回值。每个资源版本独立，正文版本见 data.tutorial.revision。

源码路径示例假设 IDE 打开的工程根目录为 mulehang-agent。若打开父工作区，路径需带 mulehang-agent/ 前缀；路由的 projectPath 同样指向实际打开的目标工程。

## 上下文、查询、定位

lessplain_context_capture
~~~json
{"question":"Flow 为什么需要 collect 才开始执行？"}
~~~

lessplain_context_read
~~~json
{"contextId":"ctx_flow"}
~~~

lessplain_materials_list
~~~json
{"kind":"tutorial","query":"Flow","limit":20}
~~~

lessplain_item_read
~~~json
{"id":"tut_flow"}
~~~
具体资源用 {"id":"tut_flow/simulations/cold_flow"}。

lessplain_source_resolve
~~~json
{"path":"shared/src/jvmMain/kotlin/com/agent/shared/agent/koog/KoogAgentGateway.kt","context":"override fun run(request: AgentRunRequest): Flow<AgentStreamEvent>","focus":"Flow<AgentStreamEvent>"}
~~~

lessplain_reveal
~~~json
{"id":"src_return"}
~~~
产物或批注也用其返回的 ID。

## 理解图

lessplain_graph_create
~~~json
{"contextId":"ctx_gateway","title":"运行入口","graph":{"nodes":[{"id":"gateway","label":"返回执行事件流","sourceId":"src_return"}],"edges":[]}}
~~~
指定 parentId 可挂到父图下。省略为根图。

lessplain_graph_revise
~~~json
{"id":"graph_gateway","expectedRevision":1,"graph":{"nodes":[{"id":"gateway","label":"准备执行事件流","sourceId":"src_return"}],"edges":[]}}
~~~
节点和边 ID、顺序、端点及关系类型保持不变。证据用 evidence:[{id,sourceId,note?,dependencySourceIds:[]}]。

## 教程和资源

节点细讲的逐点边界写在同一份 markdown 中，不增加 MCP 工具或平行文档：

~~~markdown
<!-- lessplain:step stable_point_id source=src_resolved -->

## 当前讲解点

一小段必要说明，补充声明可用 [类型名](lessplain://source/src_declaration) 跳转。
~~~

标记独占一行，后接空行；ID 在当前正文内唯一且后续保留。source 为 source_resolve 返回的主锚点 ID，概念点可省略。Agent 新增和修订会校验格式、重复 ID、空讲解点与来源引用。普通文章无标记时保持原有预览。源码联动高亮不改选区；用户主动点击普通源码链接仍走明确跳转。

lessplain_tutorial_create
~~~json
{"contextId":"ctx_flow","title":"Flow 何时开始生产值","markdown":"# Flow 何时开始生产值\n\nFlow 在收集时才开始提供值。\n\n[项目返回类型](lessplain://source/src_return)\n\n![事件传递](lessplain://diagram/delivery)\n\n![情境对比](lessplain://simulation/cold_flow)","diagrams":[{"id":"delivery","graph":{"nodes":[{"id":"producer","label":"生产者"},{"id":"collector","label":"收集者"}],"edges":[{"id":"delivery","from":"producer","to":"collector","label":"交付值"}]}}],"simulations":[{"id":"cold_flow","language":"kotlin","code":"suspend fun demo(collect: Boolean) {\n    val f = flow {\n        println(\"开始生产\")\n        emit(\"你好\")\n    }\n    if (collect) f.collect { println(it) }\n}","scenarios":[{"id":"off","label":"不收集","steps":[{"highlightLines":[2],"caption":"创建 Flow，没有执行生产逻辑。"},{"highlightLines":[6],"caption":"跳过收集，没有输出。"}]},{"id":"on","label":"开始收集","steps":[{"highlightLines":[6],"caption":"开始收集。"},{"highlightLines":[3],"caption":"执行生产逻辑。","stdout":"开始生产\n"},{"highlightLines":[4,6],"caption":"发出值并打印。","stdout":"你好\n"}]}]}]}
~~~

lessplain_tutorial_edit
~~~json
{"id":"tut_flow","expectedRevision":1,"edits":[{"find":"Flow 在收集时才开始提供值。","replace":"这里的冷 Flow 在开始收集后，才执行生产逻辑。"}]}
~~~

lessplain_diagram_save
~~~json
{"tutorialId":"tut_flow","id":"delivery","expectedRevision":1,"graph":{"nodes":[{"id":"producer","label":"生产者：提供值"},{"id":"collector","label":"收集者：处理值"}],"edges":[{"id":"delivery","from":"producer","to":"collector","label":"依次交付"}]}}
~~~

lessplain_simulation_save
~~~json
{"tutorialId":"tut_flow","id":"conditional_output","expectedRevision":0,"language":"kotlin","code":"fun demo(enabled: Boolean) {\n    if (enabled) println(\"你好\")\n}","scenarios":[{"id":"off","label":"enabled=false","steps":[{"highlightLines":[2],"caption":"条件不成立，没有输出。"}]},{"id":"on","label":"enabled=true","steps":[{"highlightLines":[2],"caption":"打印问候。","stdout":"你好\n"}]}]}
~~~

## 批注、回答、反馈

lessplain_annotations_list
~~~json
{"materialId":"tut_flow","kind":"question","status":"pending","limit":20}
~~~
省略 materialId 查询项目。图注释没有语义分类；kind=feedback 返回 feedbackEligible 的候选，须由用户或 Agent 明确确认。

## 节点细讲、阅读目录与偏好

工具数量仍为 15，以下为既有工具的可选字段。

- 图节点可带 `tutorialId`，指向已保存的教程。先用 tutorial_create 保存正文，再将返回 ID 写入 graph_create/graph_revise 的对应节点。同一节点只有一个细讲入口；读取图也返回该 ID。修订时省略该字段保留既有关联，提供新 ID 替换关联；新引用不存在或已删教程时保存失败。
- tutorial_create 可带 `folderPath:["Agent 完整流程","第一遍"]`，首次将教程引用放入嵌套目录。空列表保持默认目录；重试保留用户已安排的位置。目录移动和改名不复制正文，界面支持多个目录引用同一材料。
- context_capture 与 context_read 返回 `readerPreferences:{guidance,projectBackground}`。前者为本机用户的通用讲解要求，后者只属于当前项目；可能均为空。记录是读取时的当前偏好，冻结问题与源码不随之改写。其他工具不重复携带，Agent 本轮明确要求优先；插件不根据提问次数推断能力。
- 细讲中的补问先用 tutorial_edit 更新同一正文，再用 question_answer 完成原问题；单独回答问题不会自动修改正文。

lessplain_question_answer
~~~json
{"questionId":"q_collect","markdown":"尚未开始收集，所以 flow 内的 println 还没有执行。"}
~~~
引用已创建产物可增加 "materialId":"graph_gateway"。创建产物不会隐式完成问题。

lessplain_feedback_consume
~~~json
{"ids":["feedback_scroll","feedback_font"]}
~~~
只消费显式指定的节点待答注释。返回全文后清理；普通读取无副作用。相同参数重试返回原结果。

## 错误与重试

REVISION_CONFLICT 附 currentRevision；重新读取后按新版本提交。TEXT_NOT_FOUND / AMBIGUOUS_TEXT 要求修正 find。AMBIGUOUS_SOURCE / AMBIGUOUS_FOCUS 要求提供唯一源码上下文，候选行在错误消息中。RESOURCE_NOT_FOUND、INVALID_LINE 分别定位资源引用和模拟行号错误。SOURCE_STALE 保留材料，不导航到同名词。

DELETED 表示教程已由用户删除。停止对该教程及其批注写入，重新查询现有材料；旧创建请求重试仍返回 DELETED，不会复活原教程。只有用户明确要求重新生成时，才捕获新的上下文创建新教程。DELETING 表示正在删除，等待后重新读取状态。PERSISTENCE_FAILED 表示本次写入尚未可靠落盘，不应视为保存成功。保留原参数重试；插件会重试失败的存储写入，正文、资源、批注位置及所属写入回执作为同一快照保存。删除登记失败时仍可在界面重试；CLEANUP_PENDING 表示删除已生效，文件清理将在重试或重开项目时继续。删除在界面右键菜单执行，MCP 没有删除工具。

列表支持 limit=1..100、cursor。普通读取不会清理笔记、问题或反馈。
