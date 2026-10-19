# JCEF / React 实验实现

本分支的实现说明优先于旧设计中的 Compose、Java2D、JVM ELK 和两工具协议。用户此前否定的 JCEF 展示不作为已验收设计。

## 边界

React 负责插件页面、问题树、图内输入与弹层、图和教学组件。JVM 负责平台入口、JCEF 生命周期、MCP、精确源码验证与导航、原生编辑器 Inlay/hover/选区提问弹层，以及独立 SQLite 权威状态。工具窗口与文件编辑器共用静态应用，各自拥有浏览器。源码不再复制为画布外快照。

前端为 TypeScript/Vite/Tailwind 4，React Flow 管理图交互，elkjs 独立 Worker 管理布局，json-render 控制组件目录与结构化呈现，官方 Zustand adapter 连接教学状态 slice。局部弹层和输入继续使用局部 React 状态。没有 JVM ELK、Compose、Jewel 或自建 Java2D 图画布依赖。

图的浏览状态按结果及工具窗口/编辑器宿主区分，避免大编辑器的视口覆盖窄工具窗口。提问草稿在当前页面会话内按结果保留，并在开始输入时固定来源；已提交问题及其来源持久保存。

旧图画布、存储和 MCP 源文件已清理，Gradle 使用标准源码目录，不依赖临时 source include 清单。没有旧 MCP 别名。原生编辑器 marker、hover 状态及几何、弹层与代码预览从 605bed0 复用；它们通过 EditorSourceBinding 接到新 ViewEngine，不恢复旧问答仓储。EditorAnchorCapture、SourceNavigator、SourceSymbols、SourceAnchor、SourceDigest 继续保留。

## 呈现与提交

生成式 UI 服务于源码理解。按具体疑问选择最小表达，节点与连线足够时直接使用；需要比较的对象同时可见，空间组织与交互都须帮助理解职责、关系或状态。当前 tag 使用三个无边源码节点，缓存实验围绕“热门文章为何暂未更新”观察数据、缓存与返回。旧的多组和页签示例保留为回归材料，内容规则及本轮验证见 [生成式 UI 验证](generative-ui-validation.md)。

主流程为：用户在自己的 Agent 中提问，Agent 分析并通过 MCP 创建答案，插件展示答案。用户在节点或生成式组件上添加注释，或从原生源码选区提问，再一键复制所有待答问题回 Agent。Agent 逐条读取引用并通过 MCP 提交回复。

主页直接显示全部历史记录和简短空态，不提供新问题按钮、待答列表、复制按钮或常驻通用提问表单。工具窗口名称为 Lessplain；历史与编辑器入口使用原生 titleActions。输入由节点、组件或源码入口触发，固定来源后打开；失败保留草稿。图内右侧复制从当前理解根收集整棵追问树的待答项，并包含独立源码问题，只携带原文及必要引用，不重复源码和示例状态。复制不完成问题，提交回复才更新状态。

左上为缩放、显示全图、一键自动排版，左下为上/下一步，右侧中间为复制、理解层级和画布行为。步骤从首个 start、无入边节点或首个节点开始，多个前驱/后继必须由用户选择。理解层级浏览答案及追问树，不冒充图内分组。画布行为保存自动/横/纵方向、自动源码跳转、路径流动及跟随；默认 AUTO 按宽度 800 分界，流动/跟随开启，工具窗口自动源码跳转开启，编辑器关闭。

页面统一为 ViewDocument，Graph 提供逻辑节点与组内边，与文本、字段和示例共享选择和状态。sources/demos 使用页面内稳定 ID。教学示例 HTML 保存到记录，在 allow-scripts、无 allow-same-origin 的 iframe 中运行。接受 resize、setState、openSource、ask；宿主附加的 cursor 消息仅允许有限光标语义。样式和脚本随资源保存，网络连接禁止。

Board.children 引用 Cluster；Cluster 通过 diagram 插槽包含至多一张 Graph，通过 companion 包含普通组件。整页只有一个 React Flow 视口，分组、节点和组件进入同一坐标系并一起缩放；ELK 统一计算分组边界、节点尺寸和连线。Board.edges 的跨组端点显式指定 elementId/nodeId，Graph.edges 仍是组内关系。选择路径保持独立，导航在全局拓扑中遍历，分组导航定位分组，不使用 parentRef 追问树。

原生箭头通过固定 host/view/revision/node 的桥接请求复用画布步骤控制器，能力变化更新原生 inlay。分叉只聚焦候选，橙色候选与紫色预览区别于已确认路径；直接在图上用鼠标或键盘确认，不弹左下候选列表。起终点及分支图标保留语义配色，dependency/structure 不参与步骤。编辑器宿主不复制工具窗口标题栏或全局管理按钮。

问题入口使用 entry 区分 node、component、sourceSelection 和 sampleSelection。节点和组件问题不把锚点变成选区；所有注释都有原生查看详情，普通注释只编辑文字，sourceSelection 和 sampleSelection 显示各自冻结的代码预览。打开详情关闭网页弹层并保留新草稿。独立源码问题可从现有复制控件的右键问题集合进入详情，左键一键复制不变。

历史行右键采用小菜单，仅重命名和删除；不切换当前结果，不提供手动归属。重命名单字段编辑，删除单独居中确认。历史树无序号，保留缩进、计数和新标识。节点左下角只放注释与复制，源码跳转由节点点击和开关控制。注释弹层保留待答列表，下方仅输入框、发送和取消，无节点标题、顶部关闭图标或问题/反馈切换。新注释统一问题，旧反馈兼容；发送成功清空关闭、失败留文，取消清空新草稿关闭，Escape/外部关闭保留草稿。每条统一显示序号、正文、查看详情与删除，编辑及取消编辑在原生详情中完成；新草稿独立于旧问题编辑状态。屏幕弹层位于图标下方 6px，必要时翻转并约束到视口，不进入节点测量。tooltip 横排，右侧控件向左；按钮激活、弹层打开及程序聚焦不弹提示。可用控件正常前景色、明显灰色 hover，发送默认透明而 hover 蓝底；保留按下和聚焦。固定工具条使用统一 SVG 与紧凑分组，菜单沿用 IDE 字体、配色和缩放。

历史普通容器中的 Graph、Board 和 Cluster 在展示时投影到统一画布，原元素、节点、源码和状态路径身份保留，持久文档不改写。失配的纯组件镜头与手动位置不再用于恢复出的图。无法解析或未进入画布的图明确报错，不静默跳过。新提交的含图页面强制使用 Board、Cluster 或 Graph 根，普通容器只进入 companion。

图页不渲染重复的标题、原问题、图外前言或源码快照；标题在组内。节点只默认显示短 label，detail 不作为常驻卡片。旧手势在图内显式路由，跟焦使用 240ms 动画并保持当前缩放。再次点击当前节点仍居中，但不重复跳源码或修改路径；重复组件同步继续去重。节点操作栏通过独立画布状态保证只有一个活动项，120ms 渐显，离开连续区域立即隐藏，键盘聚焦同样可用。拖动坐标不订阅整图内容，松手只保存手动位置，不请求 ELK；布局仅随结构、方向、测量变化及显式排版触发。

catalog 从 TypeScript 组件定义导出到插件资源。JVM 使用完整参数 schema 校验，并检查根组件可达性、循环、重复父节点、图端点、源码/示例引用与大小。默认 json-render schema 较宽松，因此使用同一 catalog 的 Zod 参数定义补全检查。真实引用由当前 IDE 文档及 PSI 验证。

lessplain_context 固定来源、目标版本和原问题；lessplain_present 完整替换，在同一事务把版本化原问题保存到回答的 answeredNote 并物理删除待答行；lessplain_finish_feedback 只确认当前反馈。历史 note: 从回答快照重建，回答树及冻结来源保留。deleteNote 硬删除精确版本的待答行及所有未提交上下文，不能复活旧引用或删除回答历史。相同 context 和标准化内容去重。并发回答、删除、版本冲突或问题编辑不会覆盖保存结果。

启动时事务升级旧 answered/cancelled 行：先将已答快照写到对应回答，再删除原行，清理失效上下文。旧回答已被单独删除而留下的孤立问题恢复 pending，保留原文；迁移可重复运行。原生 Swing 问题详情提供独立删除与取消编辑，订阅快照后发现冻结引用失效就锁为只读并保留当前文字；新选区草稿独立保存。源码预览仍是 IDEA Editor。

原问题、origin 和 parentRef 独立保存。排序按创建时间、层级缩进呈现，历史不显示序号。记录以外还保存浏览状态、示例状态和未完成追问。旧数据不自动导入。

SourceAnchor 与 ResolvedSource 可带 selectionRange，冻结原生 Document 的 UTF-16 半开区间。保存校验全文版本、范围及范围原文，不要求选中文字全文唯一，也不在范围失配时搜索替代位置。原生捕获、待答、上下文、回答快照及导航全链路保留；旧无范围记录仍用安全文本/符号定位，歧义明确报错。Agent 的 SourceReference 输入不变。

宿主快照按 ref/revision 复用未变化视图；注释和新增追问仅更新相关列表/标记。已有节点保留测量、ELK 尺寸、手动位置及边 sections，不把初始默认尺寸写回；元数据刷新不触发布局或镜头。cluster 背景穿透到 pane，只有标题捕获分组拖动，内部空白右拖平移整图。关系 label 只放必要短条件，summary 通过悬停或键盘聚焦按需显示，不自动补 kind 标签。

elkjs Layered 无法固定任意手动节点坐标后只路由边。自动布局使用 ELK sections；手动拖动移植旧版内建局部修线思路，采用短 stub、障碍外通道和候选评分。按帧合并，只修受影响边，不调用 ELK 或持久化。整体拖动只从 cluster 标题发起；内部路径及标签随组平移，跨组边局部修复。重叠节点仍产生有限路径，孤立交叉不保证消除；未引入 Libavoid 或其他路由库。

ResizeObserver 实时记录视口尺寸；AUTO 只在首次、显式切换方向及自动排版时按当前宽度的 800px 分界解析，伴随组件逻辑宽度也在首次及自动排版时确定。普通 resize 不请求 ELK 或 fitView。伴随组件按稳定身份保留已测量最大空间，短内容不回缩；首次增长时以所属分组为定位锚并保持镜头。结构替换重置空间。测量按帧合并，去掉原 160ms 与 60ms 固定等待；仅实际结构、方向和成员尺寸改变才请求布局，分组测量不反馈到 ELK。过期 Worker 立即终止，新任务不等待旧任务或其 8 秒超时。

通用 HTML 控件默认样式放入 Tailwind base 层，避免覆盖 json-render 的页签选中态、按钮和菜单状态类。主题分别导出普通、次要、禁用和控件前景色，补齐 primary/accent 前景色；菜单 hover 使用合成背景，工具按钮保留原始透明覆盖色。自定义主题的不可读前景色回退到可读颜色。

删除页面子树在同一事务清理其 browse、notes、无存活结果的 commits、无法继续提交的 contexts 及绑定该子树的 source 引用。有效独立任务保留已冻结 origin；其他存活结果的幂等收据继续有效，包括回答归档后已无原 ticket 的收据。旧提交不能返回已删除页面。

## 宿主

使用 synthetic HTTPS origin 映射 JAR 中的静态资源，Worker 与页面同源。响应设置 MIME 和 CSP；URL 不映射到任意本机路径。前端 ready 独立于 CEF onLoadEnd。消息按页面 generation 隔离，快照合并，异步发布带单调 sequence，较大消息分块传输。

首个 HTML 在 UTF-8 元信息后注入已捕获的 IDE 主题和宿主身份，早于前端模块与 ready。CEF 请求线程不读取 Swing 主题；LAF 更新保留实时同步。颜色导出保留真实 alpha，完全透明和半透明色不会变成白底；缺失 hover/pressed 色时使用当前背景与前景的灰色混合。公开 setPageBackgroundColor 使用已捕获的背景颜色值。此改动消除代码中的浅色 CSS 初始化阶段，离屏验证 bridge 等待时的深色背景；未在真实 IDE 冷启动中复现或确认 CEF 原生首帧行为。

JBCefJSQuery 使用公共 JBCefBrowserBase 重载。回调线程不执行 IDE 操作；后台存储/源码读取与 EDT 导航分开。浏览器、query、资源请求与异步任务随宿主释放，切换问题不重载页面。

参考 CC GUI 的 ready、资源与生命周期处理，核对了 Amazing Claude Code GUI 和 DBML。仅借鉴机制，没有复制其整套产品实现。

## 验收

### 内部模式诊断

在加载 Lessplain 的沙盒 IDEA 开启内部模式并将焦点放在 Lessplain 页面后，通过 Find Action 搜索 `Lessplain: Open DevTools`。入口由当前组件的 UiDataProvider 提供，使用公开 openDevtools API，保留网页自己的画布菜单。诊断动作只在内部模式可用；日常开发源码的 IDEA 不负责打开另一进程的页面。

DevTools Console 中调用 `window.__lessplainDiagnostics.events()` 查看最近 500 条视口、测量、Worker 请求/取消/完成和结果应用事件；`theme()` 获取当前 CSS 主题令牌，`clear()` 清空事件。记录仅在内部模式启用，不保存源码或交互值。Performance 面板可关联 `lessplain:*` 标记与 long-task 事件。复现时连续调整窗口，再切换页签，分别检查主线程 Layout/Style、测量次数及 Worker 时序。

若只有网页区域不响应，先取 Console、Performance 与 Worker 证据；若整个 IDE 不响应，保存线程转储并检查 AWT-EventQueue 和锁等待。调试会话通过 MCP 启动或复用。JCEF remote 模式忽略 windowed 设置的警告、退出时资源泄漏与页面冻结分别调查，不能仅凭警告认定冻结根因。

参考 [JCEF 官方说明](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html)、IDE Markdown/SVG 浏览器的资源所有权与 DevTools 入口，以及 [IDE 冻结分析](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/)。真实 IDE 的持续 resize 卡死仍待运行证据与新版验收。

### 自动化与用户验收

自动化不启动 IDE、桌面或服务。JVM 回归检查事务和实际 MCP schema；前端检查共享状态及无服务器离屏浏览器的真实 Worker、布局几何、iframe 状态和提交事件。打包和两版兼容验证单独运行。

实际视觉质量、触控板和缩放手感、IDE 字体主题与源码交互仍需用户安装后验收。混合教学示例保存在 `examples/redis-cache-ranking.view.json`。实际 Redis 历史此前经 MCP 更新到第 3 版：三个职责、16 节点、13 边、26 处来源。入口锚定方法声明、缓存命中锚定 @Cacheable，其余按条件/调用/返回区分，源码经 IDEA read_file 核对，冻结选区未改。本轮 `examples/redis-history.view.json` 进一步把长连线标签移到 summary，保留全部身份与来源；已通过恢复后的开发 MCP 提交并回读核对，实际记录为 `view:8ffbcb52-c757-4461-8e21-83e0f6863494` 第 4 版，没有直接写库。


`examples/tag-concepts.view.json` 是已通过实际 catalog 校验的生成式对照页，将文章标签、配置展示标签及页面切换选项分别放入三个 Cluster，通过 Select、JsonView 和 SourceLink 对照数据与实现；业务源码入口默认折叠。14 处引用绑定真实声明或调用。开发 MCP 中的独立答案 `view:1e7083e1-2624-4318-855b-dcfa9474673a` 已更新为第 2 版并回读核对。协程演示独立保存为 `view:787c52da-d715-45fd-a010-9838d3b93970` 第 1 版；原生只读 Kotlin 编辑器已在沙盒中观察确认，未执行代码。

## 教学代码与诊断补充

`ViewDocument.codeSamples` 保存独立 `{title,language,code}` 资源；`CodeSample` 组件及 Graph 节点的 `sample: {id,startLine?,endLine?}` 指向同一资源，范围为一基闭区间。它和项目 `sources` 分开，不能通过教学代码接口读取本机路径。原生 LightVirtualFile 默认只读、按页面/资源复用；当前代码范围仅保留一处高亮。选区追问用 sampleSelection 冻结全文、摘要和 UTF-16 范围，越出节点范围则不继承节点身份。样例变化拒绝旧选区保存，历史问题继续读冻结版本，删除页面关闭相应内存标签。

编辑器右键的“Lessplain: 创建可编辑练习副本”只在用户主动调用时创建 Scratch，不自动执行代码或安装依赖。组件与演示共享 Graph.selectionPath；演示消息 openSample 只导航已保存资源。iframe 接收宿主主题变量，容器背景沿用 --card，避免白底与深色主题前景色冲突；保持隔离与网络禁用。组件标记按精确来源订阅元数据，不改变布局；重命名不重置共享状态。

`examples/tag-concepts.view.json` 将三个职责并排放进三个 Cluster，保留原组件 ID 与 14 处来源；业务源码入口默认折叠。`examples/coroutine-waiting.view.json` 用两个相同线程场景对照挂起/阻塞，图、状态和只读代码联动，明确标记为推演。它不代表用户项目实现或某一次实际调度。

内部模式增加 Find Action `Lessplain: Capture Browser Diagnostics`。它在后台读取 browser id/关闭/加载状态，通过公开 DevTools client 异步查询，单次请求 3 秒超时，同一浏览器最多 10 次采样且不并发。生命周期和 renderer 退出事件在页面 JS 之外记录；DevTools 打开前后记录线程及耗时。事件缓存最多 500 条，不记录源码、问题、bridge payload 或原始页面 URL。所有探针在宿主释放时解除注册。

开发 `RunIdeTask` 提前启用 `ide.browser.jcef.log.level=info`，日志留在沙盒，不改变已安装插件的用户设置。本轮旧进程采样显示 EDT 空闲、没有 renderer 子进程，但原生日志关闭；没有足够证据确定卡死原因。下一轮复现需关联原生事件、CDP 超时与 JS/Worker 时序，再决定修复；不得因 remote 模式警告直接切换 GPU/remote 配置。
