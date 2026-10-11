# Lessplain

Lessplain 是一个 JetBrains IDE 的插件，用来帮助理解陌生代码及其调用链路。灵感来自 [Understand-Anything](https://github.com/Egonex-AI/Understand-Anything) 和 [Walkthrough](https://github.com/forketyfork/walkthrough-plugin)

以往你向 AI Agent 提问，Agent 读源码后只是输出了一大堆解释文本，而且非常不适合像我一样的初学者，如果你经常使用 GPT 模型进行学习，你就会知道我在说什么，长此以往，阅读障碍都被练出来了。以下是一个**绝望**的例子

```text
我：A 是什么意思？有什么作用
Agent：A 是......。但是在其中混入了你还不知道的 B、C 等概念，因此理所当然地，
你问：那B是什么（因为是输出了一大段，所以你在看到B的时候就已经很疑惑了，根本不会想去看C）

于是，Agent又引出了D，E等概念，此时我就已经迷失了，唯一的选择就是硬着头皮继续看，
但大多数时候我都放弃了
```

而这个插件要做的，就是尽量解决这种痛苦，把答案画成一张图交给插件。图里的每个节点都对应一段真实代码，点一下就能跳过去。看不明白的节点可以直接追问，可以一直嵌套询问，回答会画成新图，挂载到对应的节点下面。

所有问题、图和源码依据都保存在项目本地。

不想看大段 AI 生成的 README ？看下面的演示视频就够了

TODO 待补 演示视频 中/英文版

## 它能做什么

- **用图回答问题。** 流程、调用、数据来源、条件分支这类问题，Agent 画成节点和连线，而不是几屏文字。
- **点节点跳源码。** 节点锚定到具体代码，点击后打开并定位。当前文件已经打开时平滑滚动过去，源码里也会在对应位置显示一条提示，悬停时高亮关联代码。
- **沿着图读代码。** 点一个节点，插件展开所有能走到它的路径。用“上一步／下一步”顺着连线读，遇到分叉时你自己选走哪条。控制流、调用、数据流、结构、依赖五种关系都能读。
- **在节点上追问。** 需要不停嵌套询问 side question ？在对应节点写下你的疑惑即可。Agent 之后会一次读走所有待答注释，逐条画图回答，答完注释自动消失。回答是挂在该节点下的新图。
- **看追问。** 有追问的节点会显示“？”，点它进入这个节点的追问画布。追问可以一层一层往下，窗口里的“返回”和“层级”菜单带你回到任意一层。
- **看历史。** 欢迎页显示最近记录，历史页可以搜问题和回答。

## 需要什么

- JetBrains IDE 2026.1 或更新版本。开发时用 2026.1.5 编译，2026.2.3 实测。
- IDE 自带的 MCP Server 已启用。插件还依赖 IDE 内置的 Compose，不需要 Diagrams。
- 一个能连接 IDE MCP 的 AI Agent。

## 安装

后续个人认为比较成熟后会上传到 JetBrains Marketplace，目前仅限压缩包安装。

1. 拿到安装包 ZIP，没有现成的话参考下面“从源码构建”。
2. 在 IDEA 中打开 Settings → Plugins，点齿轮图标，选 Install Plugin from Disk，选择 ZIP，重启 IDE。
3. 让 Agent 连上 IDE 的 MCP Server。
4. 把仓库里的 `skills/`（含 `SKILL.md` 和 `references/`）复制到 Agent 的 skills 目录下的 `understanding-frames/` 中。这个 skill 告诉 Agent 什么时候该画图、图该怎么画、怎么处理待答注释。

> [!TIP]
> 强烈建议根据个人情况修改一下这个 skill：你是希望尽可能详细地解答（具体到代码逻辑块或者每一行），还是仅链路清晰（一般情况下一个函数就对应一个节点）就够了。
>
> 默认的 skill 只是我个人的偏好，不一定会对你的胃口。

## 怎么用

1. 打开 IDE 右侧的 Understanding 工具窗口，保持项目打开，确保 Agent 已连接 IDE MCP。
2. 向 Agent 问一个关于代码的问题，比如“这个请求从进来到写库经过哪些步骤”。最好手动触发一下 skill 。Agent 读源码后把图提交给插件，工具窗口里出现新记录。
3. 点节点读代码。单个值、类型这种一句话能答完的问题，Agent 会直接在对话里回答，不画图。
4. 想深入哪个节点，就给它写一条注释，再让 Agent 回答待答注释。回答出来后，这个节点会出现“？”。

### 图上的操作

| 操作         | 效果                     |
|--------------|--------------------------|
| 滚轮         | 上下平移                 |
| Shift + 滚轮 | 左右平移                 |
| Ctrl + 滚轮  | 以光标为中心缩放         |
| 右键按住拖动 | 在任意位置平移           |
| 右键单击节点 | 打开节点菜单             |
| 左键拖节点   | 手动调整位置，连线跟着走 |

图内还有缩放、全图、一键整理、连线流动、固定源码跳转和自动跟踪节点这几个开关。一键整理只清除当前方向的手动位置。每条历史的根图和每个节点的追问图各自记住这些开关、当前路径、缩放、视口和手动位置。

## 数据存在哪

项目的 `.idea/lessplain/understanding.db`，是一个 SQLite 文件，包含问题、回答、图的各个版本、源码依据和浏览状态。

- 同一项目在第二个 IDE 实例里打开时，数据库只读。
- 数据库结构升级前会先备份，升级失败会回滚。

## 源码定位的限制

定位基于当前编辑器里的内容，包括没保存的修改。节点会记录文件、符号、一段文本和文件哈希。

- 同一段文本出现多次时，插件不会替你猜是哪一处，界面显示定位失败。
- 文件改过但片段仍然唯一、所属符号也对得上时，会重新定位，但不代表原来的解释还成立。
- 源码变化后的证据对比提示还没做完。跨文件移动和依赖语义变化需要更完整的语言适配。

## 目前没有的东西

- 真实运行的 Trace
- 对代码改动的完整 Diff 理解
- 覆盖整个项目的全局图
- 自动把任务交给 Agent。现在只能复制节点来源，再粘贴给 Agent 或 添加注释后主动询问

产品目标和还没实现的部分见 [设计文档](docs/code-understanding-design-v0.5.md)。（不建议人工阅读）

## 给 Agent 开发者：MCP 工具

插件向 IDE 的 MCP Server 注册了两个工具。

| 工具                        | 作用                                                                                    |
|-----------------------------|-----------------------------------------------------------------------------------------|
| `get_understanding_context` | 读取当前来源或固定来源，返回 `contextToken`、协议版本、`capabilities`，以及待答注释队列 |
| `present_understanding`     | 提交一张理解图并保存。必须带图，纯文字回答不会被接受                                    |


`graph` 参数是 JSON 字符串，形如：

```json
{"nodes":[{"id":"start","label":"检查输入","anchor":{"path":"src/Main.kt","symbol":"main","text":"if (valid)","version":"内容哈希","lineHint":12}},{"id":"yes","label":"继续保存"}],"edges":[{"id":"valid","from":"start","to":"yes","relation":"control_flow","condition":"valid=true","coverage":"当前分析范围"}],"evidence":[]}
```

- 节点和边都用稳定 ID，不要提交坐标，布局由插件算。
- `relation` 可以是 `control_flow`、`call`、`data_flow`、`dependency`、`structure`。只有 `control_flow` 的 `condition` 会显示在线上，同一节点有多条控制流出边时每条都要写。
- 节点的 `flowRoles` 可以是 `["start"]`、`["end"]`、`["start","end"]`，普通节点省略。结束节点可以用 `endOutcome` 标 `success` 或 `failure`，不确定就留空。提交前先确认上下文里 `capabilities.nodeSemantics` 为 true。
- 分支图标由插件根据后继数量自己算，不要提交 `decision`。
- 新记录要么设 `placement=root`，要么用 `child`、`sibling` 挂到已有记录下，要么用 `explicit` 加 `parentId`。节点追问和待答注释用 `child`。

字段细节、归属规则、待答注释批处理流程见 [协议细则](skills/references/protocol.md) （也不建议人工阅读）和 [配套 skill](skills/SKILL.md)。

## 从源码构建

需要 JDK 21。仓库自带 Gradle Wrapper，在本目录运行：

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin --offline
```

安装包生成在 `build/distributions/`。`verifyPlugin` 会检查 2026.1.5 和 2026.2.3 两个版本，离线运行需要对应 IDE 已经缓存。

开发时在 IDEA 里打开上一级的 `Agent-dev` 工程，用仓库里的 `Lessplain IDE` 运行配置启动开发实例（2026.2.3）。`runIde` 任务运行 2026.1.5，用来复查最低兼容版本。代码约定、运行配置的细节和 MCP 连接说明见 [AGENTS.md](AGENTS.md)。

## 更多文档（最好给 Agent 看）

- [设计文档](docs/code-understanding-design-v0.5.md)
- [更新记录](CHANGELOG.md)
- [历史交接记录](docs/agents/)：每一轮改动的实现细节、测试结果和待验收项
