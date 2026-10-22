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

列表支持 limit=1..100、cursor。普通读取不会清理笔记、问题或反馈。
