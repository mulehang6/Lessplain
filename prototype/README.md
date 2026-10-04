# Understanding 交互原型

React + TypeScript + Vite 原型，围绕问题树、源码、关系图与历史 Trace 回放。界面中的源码经过简化，Trace 均为合成事件。没有接入 IDEA、Agent、PSI 或 Debugger。

本目录只保留交互原型，真实插件已在上级工程中开发。完整方案见 [主设计文档](../docs/code-understanding-design-v0.5.md)，当前插件状态见 [根目录 README](../README.md)，产品与协作原则见 [AGENTS.md](AGENTS.md)。原型中的演示方式不直接作为插件设计。

早期技术建议与选型过程见 [实施草案](../docs/agents/implementation-draft.md)。原生 Diagram 已完成评估，当前插件使用自绘图。

规划包含同一工具窗口内的独立历史页、全部问答的分组保存与搜索、图打开到编辑器、静态分支选路，以及 diff 理解和就地追问；这些尚未在当前原型实现。具体差距见主设计文档末尾。

## 打开

推荐在 IDE 中运行 `package.json` 的 `dev` 脚本，包管理器选择 pnpm，工作目录为 `prototype`。沿用现有的 pnpm `run dev` 配置即可。首次安装依赖或通过终端启动时使用：

```powershell
pnpm install
pnpm run dev
```

验证与打包：

```powershell
pnpm run check
```

`check` 执行数据模型测试、组件交互测试、TypeScript 检查和 Vite 构建，再生成可独立打开的单文件预览。组件交互测试使用内存 DOM，不代替浏览器视觉验收。pnpm 使用 `pnpm-lock.yaml` 锁定依赖版本。`pnpm-workspace.yaml` 的 `allowBuilds.esbuild: true` 允许 esbuild 安装所需的构建脚本。

不需要开发服务时，也可以在构建后直接打开 [dist/preview.html](dist/preview.html)。

## 建议体验顺序

1. 在右上角“体验场景”选“看一次分支”。图底部直接回放、上一步、下一步；运行值贴在节点上。点击节点同步定位源码。
2. 选择“看三次重试”，直接点“尝试保存”节点上的第 1 / 2 / 3 次，结果分别为 timeout、busy、ok。无需展开事件列表。
3. 选择“代码改了以后”，点橙色节点上的“查看变化”。先对比新旧代码，再通过“复制核对问题，发给 Agent”把问题带回自己的 Agent。
4. 场景提示中的“演示下一步：收到更新后的图”载入预制的新图，用于展示 Agent 返回结果之后的状态。这个按钮不属于生产插件，也不执行真实分析。旧回放保持旧版本标记。
5. 选择“继续追问草稿”，先看到具体问题“草稿怎样随新会话一起恢复？”，再点“演示：看 Agent 的回答”。问题树与图标题展示它属于哪个问题；“回到提问处”恢复之前的节点和回放位置。
6. 项目关系图仍保留。点击区域跳源码，“依据”按需查看，不常驻一个详情面板。
7. 刷新页面，各问题的节点与回放位置会从浏览器本地恢复，并兼容旧版本的本地记录。

浏览器若阻止剪贴板访问，页面提供手动复制文本。若不允许本地存储，状态栏会说明无法保存。

## 设计与边界

- 产品原则、交互、数据契约和实施验证统一维护在 [主设计文档](../docs/code-understanding-design-v0.5.md)。
- Agent 工作流草案：[配套 skill](../skills/understanding-frames/SKILL.md)，保存在插件工程内，未安装到全局技能目录。
- 原始参考：[v8 HTML](code-understanding-prototype-v8-trace-question-tree.html)。旧原型保留原样，里面的按钮与伪代码不代表当前约定。
- 源码锚点在 React 中只做文本与符号标识的模拟匹配；真实 PSI、文件重命名、符号重载与 AST 指纹等待插件实现。
- Trace 仅演示单线程、单调用、完整事件序列。并发、协程切换、采集丢失不能通过相邻 seq 推断因果。
- 项目关系图保留在第一轮范围；UI Outline 延后。
