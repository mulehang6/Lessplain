# 教材与原位补充

用户主动选择“学习选中内容”时使用教材。流程问题继续沿用图协议。

## 工作流

1. 用载荷中的 `contextToken` 调用 `get_understanding_context`。核对返回的 `context.question`、`lesson.origin`、`context.target` 与 `targetCurrent`，以冻结来源为准。
2. 读真实源码和相关定义，核对示例依赖版本。默认解释实体、概念的作用和项目中的用法，例如 `Flow<AgentStreamEvent>` 先讲 Flow 与 AgentStreamEvent，泛型和尖括号等基础由用户追问。
3. 按教学需要组合文字、代码、表格和静态图。教材自由阅读，不要求测验、逐节确认或运行。概念简化模型要明确标注，并给出真实定义的项目相对路径供对照。
4. `context.annotationId` 非空时提交 `presentation=annotationReply`，否则提交 `presentation=lesson`。沿用冻结 token，`question` 原样填写，`placement=root`，`schemaVersion=1.4`。`lesson` 是 JSON 字符串；省略 `graph` 和其他图归属参数。
5. 核对成功返回的 `lessonId`。只在成功后说明保存结果。目标失效时保留原问题，让用户重新选择；不要改用当前标签页的选区。

原位补充只追加回答块和新示例，不能改原课或个人笔记。回答内部也可继续追问，各自 token 相互独立。重试相同请求须保持 `requestId` 与内容一致，修改内容换新 ID。

## 教材 JSON

```json
{
  "title": "认识 Flow",
  "blocks": [
    {"id":"heading","kind":"text","level":1,"text":"Flow 在这里做什么"},
    {"id":"purpose","kind":"text","text":"先说明概念，再结合冻结的项目代码说明用途。"},
    {"id":"members","kind":"table","columns":["实体","作用"],"rows":[["Flow","向收集者提供一系列值"],["AgentStreamEvent","项目定义的事件类型"]]},
    {"id":"example","kind":"code","exampleId":"events"},
    {"id":"diagram","kind":"diagram","graph":{"nodes":[{"id":"producer","label":"产生事件"},{"id":"collector","label":"收集事件"}],"edges":[{"id":"events","from":"producer","to":"collector","relation":"data_flow"}],"evidence":[]}}
  ],
  "examples": [
    {"id":"events","fileName":"events.main.kts","code":"// 教学简化模型，真实事件定义请对照项目源码\nimport kotlinx.coroutines.*\nimport kotlinx.coroutines.flow.*\n\ndata class DemoEvent(val label: String)\n\nrunBlocking {\n    val events: Flow<DemoEvent> = flow {\n        emit(DemoEvent(\"Started\"))\n        delay(100)\n        emit(DemoEvent(\"Finished\"))\n    }\n    events.collect { println(it.label) }\n}\n","dependencies":["org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"]}
  ]
}
```

上述版本只是格式示例，生成时查目标项目实际版本。一个回答内的块 ID 唯一，示例 ID 在整课内唯一。`level=1..3` 是标题，省略或 0 是正文。用独立 code/table/diagram 块表达对应内容，不把 Markdown 表格或代码围栏塞进正文。

## 原生富文本与精确关联

`lessonProtocol.version=2` 支持以下可选扩展，旧纯文字教材继续有效。

正文可用 `richText` 替代 `text`，例如 `[{"text":"Flow","style":"code"},{"text":" 陆续提供值。","style":"plain"}]`。可用样式为 `plain/strong/emphasis/code/link`，链接另填 `href`，仅支持 http/https。插件按顺序拼接片段得到规范文本，样式不占字符；不要同时重复填写 `text`。

每个教材块可提供 `sourceBindings`，分别关联实际概念。下面把正文开头的 Flow 关联到函数签名中的 Flow：

```json
{"id":"purpose","kind":"text","richText":[{"text":"Flow","style":"code"},{"text":" 陆续提供值。"}],"sourceBindings":[{"id":"flow","label":"Flow","start":0,"end":4,"focus":{"anchor":{"path":"src/Gateway.kt","text":"fun run(): Flow<Event> = channelFlow {"},"start":11,"end":15}}]}
```

外层 `start/end` 可限定正文规范文本中的对应范围，省略表示整块。`focus.anchor.text` 是源码文件中唯一的连续定位上下文，`focus.start/end` 是这个上下文内的准确字符范围。偏移均为 UTF-16，`end` 不包含。多个同名词要靠上下文区分，不能只提交 `Flow` 并让插件猜位置。插件会验证真实文档、上下文唯一性、版本和范围，失效时拒绝保存新关联。

一段同时解释两个概念时，分别提供关联及正文范围，或拆成两个块。追问和笔记会归入对应的源码范围，回答内没有新关联时沿用其问题的关联。未提供可靠对应时保留来源，不编造绑定；旧教材没有精确关联时仍可从学习材料列表打开。

不要把教程渲染成功描述成真实程序运行成功。完整的新格式示例见仓库的 `docs/examples/flow-native-lesson.json`，生成到插件时须先读取冻结 contextToken，再提交新的 requestId。

图复用 nodes/edges/clusters 模型，由插件自动布局，可给概念对象建节点而不伪造源码锚点。图不含坐标、HTML 或任意生成组件。

引用项目真实定义时，使用独立的 `code` 块，`text` 放代码，`source` 放 `{path,text,symbol?,version?}`。`path` 为项目相对路径，`source.text` 为当前文件中唯一的真实片段，`version` 可填完整文件哈希。教材提供“查看源码”按钮；来源变化导致片段不唯一时停止跳转，不靠旧行号猜位置。

## 可运行示例

使用独立 `.main.kts` 文件名，不含目录。脚本自包含，Maven 依赖写在 `dependencies`，由插件生成 `@file:DependsOn` 头，`code` 不重复写依赖头。依赖采用项目对应版本；JVM 脚本选能解析 JVM 实现的 Maven 坐标。

代码块通过 `exampleId` 引用真实示例，此时省略 `text`。教材显示和 IDE Run/Debug 使用同一份外置文件，学习者修改的内容不会被重新生成覆盖。不可运行的源码引用或伪代码使用 `code.text`，不伪装成可运行示例。脚本不能依赖业务模块编译输出，也不创建 Gradle 模块、源码、测试或 Git 排除配置。

读取已有课程时，`exampleFiles` 包含学习者当前编辑的代码与哈希，优先参考它，而不是重新生成原始代码。原生执行可能需要用户完成 IDE 脚本依赖解析；工具提交成功只表示教材已保存，不代表例子已经运行或断点已命中。
