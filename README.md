# Lessplain

Lessplain 帮助用户看清真实代码中的职责、关系和状态变化，结果保存在 IntelliJ IDEA 项目内。Agent 按具体问题选择源码节点、关系图、短文字或必要交互，方便回到源码核对。多行代码和 JSON 通过紧凑入口打开原生只读编辑器。

当前分支 experiment/jcef-react 使用 JCEF、React、React Flow、elkjs Worker 和 json-render。这是重新实现的实验版本，实际界面仍需安装后验收。

## 使用

1. 在包含 JCEF 的 JetBrains Runtime 中运行 IDEA 2026.1 或更新版本，启用内置 MCP Server。
2. 从本地 ZIP 安装插件，让已配置的 Agent 连接 IDE MCP。
3. 将 skills/lessplain 复制到 Agent 的技能目录。需要互动教学时，另安装独立的 skills/lessplain-teach；原 teach 不受影响。
4. 在 Agent 中提出当前问题。Agent 用 lessplain_context 固定上下文，读取实际源码后用 lessplain_present 提交完整页面。
5. 插件主页显示全部历史记录。工具窗口名称为 Lessplain，原生标题栏提供历史记录和“在编辑器打开”。进入答案后，在节点或生成式组件上添加注释；也可在原生编辑器中选中代码后提问。
6. 点击图内右侧中间的“复制全部问题”，把简洁的问题列表粘贴到自己的 Agent。它收集当前理解根及其所有追问的待答项，并一并带上独立源码问题。复制成功后在插件页面内短暂提示数量，失败同样在页面内显示原因。Agent 逐条读取引用并通过 MCP 回复；提交成功后该问题才退出待答列表。

插件只暴露三个 MCP 工具。创建和更新共用 lessplain_present，反馈用 lessplain_finish_feedback 确认。完整协议见 [协议](skills/lessplain/references/protocol.md)。

## 当前能力

- React 问题树、搜索、历史、追问、反馈、标题和归属管理、删除确认。
- 工具窗口和编辑器标签页使用同一 React 应用，各自保留浏览器实例。
- React Flow 图交互；根据实测节点尺寸在独立 ELK Worker 中布局，使用 ELK 返回的正交路径。
- 整页是一个画布，Board/Cluster 组织分组；节点、生成式组件和隔离示例一起平移缩放。真实关系可跨组连接，图页无嵌套图窗、外层滚动条或重复前言。
- 图左上缩放、显示全图和自动排版，左下上/下一步；右侧中间提供复制、画布行为及有追问时的层级导航。行为设置包含方向、自动跳源码、路径流动和跟随节点，按结果及宿主保存。
- 右键平移、滚轮与触摸板双轴平移，Shift 加纯纵向滚轮横移，Ctrl 缩放；节点点击、步骤和组件选择共用平滑跟焦，节点操作采用 hover 图标。
- json-render 标准 shadcn 组件、自定义源码/图/JSON/示例组件、官方 Zustand 状态适配器。
- 自定义示例保存在结果中，在隔离 iframe 中运行，与页面共享允许的 JSON 状态。
- 独立教学代码随页面保存，在 IDEA 原生只读标签中定位；支持选区追问、版本校验和按需创建 Scratch 练习副本。交互推演明确标为演示，运行与调试按需进行。
- 已有组件注释常驻数量或历史标记，保留统一画布；字段、源码入口与练习细节可在 Cluster 内按需展开。
- 明确收起时分组边框随内容缩回，普通比较切换保留已测空间，保持分组位置及镜头。Card、Badge 支持语义色轻强调。
- JSON 与源码片段在原生只读标签查看，打开时冻结值和交互状态；源码快照另有定位当前文件的入口。可从选区继续提问，页面改版后旧入口失效，已保存问题保留原文。
- 精确源码片段校验，原生源码导航、当前选择提示，以及重新定位/变化/歧义状态。
- 原生编辑器短 Inlay 和柔和 hover 高亮，选中源码后就地打开原生提问弹层；不跳转到插件输入页。
- 原生 inline 与画布共用步骤导航；分叉会聚焦并高亮全部候选。节点注释不展示源码，源码选区问题的详情复用原生问题弹层。
- 固定原问题和来源、事务保存、网络重试去重、并发版本冲突、取消与编辑检查。

新记录保存在当前项目 .idea/lessplain-experiment/views.db。旧 .idea/lessplain 数据保留，不自动导入。尚未验收当前版本的视觉质量、JCEF 中的 Worker 行为与输入流畅度；浏览器离屏测试不能替代 IDE 实机验收。

## 构建

开发需要 JDK 21、Node 22.12 或更新版本、pnpm。安装后的插件只运行打包资源，不启动 Node 或开发服务器。

在本仓库执行：

- pnpm --dir webview install --frozen-lockfile
- pnpm --dir webview test
- pnpm --dir webview test:browser
- .\gradlew.bat test
- .\gradlew.bat buildPlugin
- .\gradlew.bat verifyPlugin --offline

离屏浏览器检查默认使用本机 Edge，不打开可见窗口。ZIP 位于 build/distributions。离线兼容检查需要缓存 IDEA 2026.1.5 与 2026.2.3。

实现和验收边界见 [实验设计](docs/jcef-react-experiment.md)。
