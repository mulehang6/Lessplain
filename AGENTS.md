# Repository Guidelines

## 项目结构与模块

插件源码位于 `src/main/kotlin/com/lessplain/`，按 `core`、`diagram`、`ide`、`mcp`、`model`、`storage` 和 `ui` 分模块。平台描述、图标和消息资源在 `src/main/resources/`；测试位于 `src/test/kotlin/`，并按主代码包路径组织。`docs/` 保存设计文档，`skills/` 保存 Agent 工作流。`prototype/` 是独立的交互原型，不属于插件源码。

## 文档职责

本文件约束开发本插件的 Agent。`skills/` 将分发给用户，用于指导用户的 Agent 使用插件，不写入本仓库的开发流程或环境约束。`README.md` 面向用户，说明安装、使用和已实现能力。新增开发规则写入本文件，接口实现说明写入 `docs/`，按任务范围更新文档。

涉及产品设计、教程、源码定位、持久化或平台集成时，从 [Agent 文档索引](docs/for-agents/README.md) 查阅对应说明。日期记录用于查证修复与验证结果，当前行为以现行设计和实现说明为准。

## 构建、测试与本地开发

使用 JDK 21 和仓库自带的 Gradle Wrapper，从项目根目录运行：

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin --offline
```

`test` 使用 JUnit Platform；`buildPlugin` 将安装包写入 `build/distributions/`。`runIde` 启动 IntelliJ IDEA 2026.1.5，`runIdeLatest` 启动开发实例 2026.2.3；`verifyPlugin` 检查 2026.1.5 和 2026.2.3 的兼容性。离线验证要求对应 IDE 已缓存。

## 代码风格与测试

沿用现有 Kotlin 和原生 Swing 写法，缩进使用 4 个空格、尾随逗号、无制表符。包名用小写，类型名用 `UpperCamelCase`，函数和属性用 `lowerCamelCase`；测试类以 `Test` 结尾。行为变更应在对应包的 `src/test/kotlin/` 中补充或更新测试，先运行针对性测试，再运行完整 `test`。图表和源码导航使用事件、几何和离屏绘制回归；实际界面验收由用户安装新版插件完成。

每个文件围绕一个内聚职责组织。生产 Kotlin 文件超过 500 行、测试 Kotlin 文件超过 800 行时，必须在同次改动中拆分；渲染、交互策略、平台适配和纯几何规则应尽早分开。移动或新增代码时保留适当 KDoc，并同步维护对应测试。

保留未来公开发布能力，优先使用 IntelliJ 公开 API。内部 API 使用不能通过反射或降低检查标准来隐藏。当前图表采用 ELK 布局与 Swing/Java2D 绘制；替换后端必须验证公开入口、兼容性和路径动画等现有能力。

## 提交与拉取请求

提交记录采用带类型和可选范围的简短格式，例如 `feat(ide): ...`、`feat(plugin): ...`、`docs: ...`。提交说明应点明变更内容。拉取请求请描述用户可见的变化和原因，列出已运行的检查；界面改动附截图，并关联相关问题。

## 本地配置

`.idea/`、构建输出和 IntelliJ 沙箱属于本地状态，已由 `.gitignore` 排除。不要提交本机数据库、IDE 配置或凭据。

## Lessplain：IDEA MCP 连接说明

本项目区分日常开发 IDE 的内置 MCP 与插件验收 IDE 的开发 MCP。调用前先确认连接对应的 IDE 及其已打开项目，将该项目的绝对路径传入路由外层 `projectPath`；不得为探测连接而自动打开工程或启动 IDE。

| 连接名          | 连接对象                                                                                | 主要用途                                                     | 工具入口                         |
|-----------------|-----------------------------------------------------------------------------------------|--------------------------------------------------------------|----------------------------------|
| `idea`          | 日常开发使用的 IDEA 的内置 MCP，当前打开包含本插件工程的工作区                          | 面向插件源码工程的日常开发操作                               | `mcp__idea__execute_tool` 路由器 |
| `idea_dev_2026` | 由 `Lessplain IDE` 启动的开发 IDE，加载本插件；其中打开的项目是验收对象，可能随测试改变 | 调用插件自身的 MCP 工具，检查开发 IDE 中的运行行为和测试项目 | `execute_tool` 路由器            |

两条连接通过各自的 `execute_tool` 路由器调用。`idea` 面向日常开发 IDE 中的插件源码工程；`idea_dev_2026` 面向插件开发实例中当前打开的验收项目。调用插件的 lessplain_* 工具时，将工具名和结构化参数交给当前路由，把验收工程路径放在外层 projectPath，不传插件源码工程路径。当前为 15 个工具，接口见 skills/lessplain/references/protocol.md。idea_dev_2026 仅用于插件自身工具及验收对象，不能代替日常开发连接。

开发默认打开父级 `Agent-dev` 工程。仓库内 `Lessplain IDE`、`Lessplain Tests` 和 `Lessplain Verifications` 三份运行配置的 Gradle 路径都是 `$PROJECT_DIR$/Lessplain`，这是父工程下的预期设置，不为单独打开插件目录而修改。启动插件时选唯一的 `Lessplain IDE`；不要选旧配置 `Run IDE with Plugin`，它属于 `walkthrough-plugin`，会把没有 Gradle 构建文件的工作区根目录传给 Gradle。单独打开 `Lessplain` 时，使用本目录的 Gradle 命令，或另建 Gradle 工程目录指向本插件 `settings.gradle.kts` 所在目录的配置。

开发 IDE 重新启动后，旧的 stdio MCP 连接可能失效。MCP 重连只恢复通信，不会把新构建的插件代码加载进仍在运行的 IDE。自动化工作不要为验证而启动开发 IDE 或截图；需要时等待用户明确要求。

测试记录保存在开发 IDE 当前打开项目的 `.idea/lessplain/` 中。写入前确认项目，使用明确标记的测试内容；不要把测试记录误认为插件工程自身的数据。

## 自动化检查

不要自行启动开发 IDE、操作桌面或截图检查界面。完成必要的命令行测试与代码检查即可；需要视觉反馈时使用用户提供的截图，除非用户明确另行要求交互检查。

## 说明
- [context.md](./docs/context.md) 必读
- 注释优先中文
