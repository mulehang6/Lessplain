# Master Understanding

IntelliJ IDEA 2026.1 起兼容的插件；以 2026.1.5 编译，开发实例使用 2026.2.3。Agent 通过 IDE MCP 提交代码理解图或补充回答；插件保存记录、固定提问来源，并在 Understanding 工具窗口查看。产品目标与未实现范围见 [主设计文档](prototype/docs/code-understanding-design-v0.5.md)，Agent 工作流见 [配套 skill](skills/understanding-frames/SKILL.md)。

## 当前实现状态

- 已实现三个 MCP 工具：`get_understanding_context`、`present_understanding`、`append_understanding_answer`。开发 IDE 已验证固定来源读取、补充回答和带来源的追问示例图写入。`append_understanding_answer` 现在可用 `replacesAnswerId` 修订同一问题的旧回答；旧内容留存，但不再作为当前回答显示。
- Jewel 界面提供顶层历史与搜索；“最近”只列顶层记录。鼠标悬停节点时，节点框下方出现复制图标，有追问的节点还出现“？”图标。一个节点的全部追问共用一张画布，每条追问是独立的 cluster；画布内的导航按钮可跳转 cluster。没有示例图的追问留在该画布内，不生成空层级。画布内的“层级”按钮列出当前记录的完整追问树，可前往祖先、同层分支或更深层；编辑器中也可使用。
- 图由插件自身的 Swing 组件绘制，工具窗口与编辑器共用布局与绘制代码。开始节点为绿色，结束节点为红色；聚焦只改变边框。阅读路径高亮连线，移动虚线沿同一条曲线流动。前后步平滑移动视口；分支处闪烁候选节点，点击选路。图内有缩放、全图、一键整理、流动、固定源码跳转和自动跟踪节点开关，上下步在左下角。节点可手动拖拽，连线随之更新；一键整理恢复自动排版。固定源码跳转后，选节点仍更新图，但不抢走图表焦点；关闭自动跟踪后，选节点不移动画布。工具窗口标题栏可将当前图在编辑器打开，编辑器中的追问会替换当前图页，标签使用短标题。`getParameter` 示例在流程图旁展示虚构的请求属性，点不同步骤会高亮对应的字段。
- SQLite 位于项目 `.idea/code-understanding/understanding.db`，保存问题、回答、固定来源、图版本及根记录的浏览状态。每条历史的根图、每个节点的追问图另有独立的项目本地视图状态，记录固定、跟踪、流动开关、当前路径、节点、缩放、视口中心和手动节点位置；工具窗口与编辑器共享这些设置。第二个实例只读；数据库迁移前备份，失败回滚。
- 选中有有效源码锚点的节点时，插件只在对应代码处添加一条 Understanding 提示，不改动其他插件的 Insight。提示右侧的上/下一步按钮沿当前理解路径导航；遇到分支时弹出选项。悬停提示时，关联代码会出现短暂的渐变高亮；切换节点后清理本插件的旧提示。固定源码跳转时，仅更新已经打开的源码编辑器。
- 源码定位使用当前文档内容，包括未保存编辑。重复文本不会自动认定为同一位置。定位结果在内部区分位置状态与证据状态；界面目前显示定位失败信息，完整的证据变化提示尚未完成。跨文件移动和依赖语义变化仍需更完整的语言适配与实测。

本批没有真实 Trace、完整 Diff 理解、项目全局图，也不接入 Agent 自动任务交接。当前只提供复制载荷的交接路径。

## 开发与安装

使用 JDK 21、仓库内的 Gradle Wrapper。在本目录运行：

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin --offline
```

安装包生成在 `build/distributions/`。在 IntelliJ IDEA 2026.1 或更新版本的 Settings → Plugins → Install Plugin from Disk 中选择 ZIP。插件依赖 IDE 自带的 MCP Server 和 Compose，不依赖 Diagrams。在 `Agent-dev` 工作区中选择 `Master Understanding IDE` 启动开发实例；若单独打开本目录，选择 `Master Understanding IDE (standalone)`。两项配置都执行本目录的 `runIdeLatest`，目前运行 2026.2.3。原有 `runIde` 仍运行 2026.1.5，供最低兼容版本复查；`sinceBuild=261` 保持不变，`verifyPlugin` 检查两个版本。若从终端运行 Gradle 命令，先进入本目录。自动化构建不会启动长期 IDE 进程。

## 使用流程

1. 在图节点上悬停并点击复制图标，插件把固定的 `contextToken`、记录 ID、节点 ID 和节点名称放入剪贴板。用户连同自己的问题交给 Agent；Agent 以 `get_understanding_context` 读取该固定来源。复制节点不会预先创建一个空问题。
2. Agent 用 `present_understanding` 和复制的 `contextToken` 为追问创建 `placement=child` 的子记录；没有现成 `questionId` 时，插件在同一事务中保存问题并返回新 `questionId`。纯文字补充用 `append_understanding_answer`，它同样可用来源 token 与问题原文创建问题。两种写入都要求 `schemaVersion=1` 和唯一 `requestId`。同 ID 同内容重试返回原结果，同 ID 不同内容报冲突。
3. 在 Understanding 工具窗口选择顶层记录，悬停并点击节点的“？”进入该节点的追问画布；画布包含该节点的全部问题 cluster，可用画布内的导航按钮跳转。只有节点确实有追问时才显示“？”。“返回”逐层退回，“层级”菜单可在整棵追问树中跳转。静态分支仍需手动选路；`control_flow`、`call` 和 `data_flow` 连线均可逐步浏览，不依赖节点类型。

`present_understanding` 的 `graph` 参数是 JSON 字符串，形如：

```json
{"nodes":[{"id":"start","label":"检查输入","anchor":{"path":"src/Main.kt","symbol":"main","text":"if (valid)","version":"内容哈希","lineHint":12}},{"id":"yes","label":"继续保存"}],"edges":[{"id":"valid","from":"start","to":"yes","relation":"control_flow","condition":"valid=true","coverage":"当前分析范围"}],"evidence":[]}
```

图中的节点、关系和证据使用稳定 ID。`relation` 可为 `control_flow`、`call`、`data_flow`、`dependency` 或 `structure`；控制流分支与依赖关系分别处理。不要提交坐标。无来源的新记录需显式设置 `placement=root`，或使用 `placement=explicit` 并提供有效的 `parentId`。

## 验收状态

自动化测试覆盖请求去重、固定来源、追问归属、顶层历史搜索、非法引用、分支选路、重启读取、只读第二实例与迁移失败保留原数据；图测试覆盖分层、共享画布、悬停点击、视口、流动线、窄宽度控件、节点及示例属性框的拖拽与恢复自动布局。视图状态测试覆盖不同历史及追问层之间的隔离和重新读取，包括手动位置。`test compileKotlin` 已通过；`verifyPlugin --offline` 判定 IU 2026.1.5 与 2026.2.3 均兼容。Verifier 仍列出既有的 deprecated 和 experimental API 使用，没有兼容性错误。图表交互仍需在新构建的开发 IDE 中目视复验。

曾验证 GraphChart v2 可通过公开 API 初始化节点，但其内置布局器不能用自定义包装器截取布局结果，直接调用 `layout()` 抛出 `NotImplementedError`；公开图句柄也未提供读取实际连线路径或控制虚线相位的接口。用户确认后，产品图改为插件自绘，使静态连线、箭头与流动虚线共用同一几何路径。离屏深色预览位于 `build/reports/understanding-graph-preview-dark.png`，仅证明绘制结果；动画交互仍待开发 IDE 目视验收。

此前 2026.1 开发实例日志出现 IDEA 平台启动时的 `NonBlockingFlushQueue` 初始化竞态，插件已加载且实例由用户手动关闭。开发目标已升至 2026.1.5；两次开发实例启动都加载了插件，日志未再出现该异常。竞态可能间歇发生，真实界面与 MCP 交互仍须单独验收。

开发 IDE 中已有真实 MCP 写入和手动界面试用。下一轮重点核对：从“最近”切换历史时标题、图和源码标识是否一起切换；每条历史及其追问层的固定、跟踪、路径和视口是否分别恢复；工具窗口与编辑器同时打开同一图时开关是否同步。自动化测试和 Plugin Verifier 不能替代这些界面检查。

`runIde` 和 `runIdeLatest` 在启动前会同步各自的插件沙箱。构建脚本保留在开发 IDE 内手动安装的其他插件，只替换正在开发的 Master Understanding；两个运行配置仍使用不同的沙箱，安装的插件不会自动跨沙箱共享。
