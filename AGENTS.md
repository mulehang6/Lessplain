# Repository Guidelines

## 项目结构与模块

插件源码位于 `src/main/kotlin/com/master/understanding/`，按 `core`、`diagram`、`ide`、`mcp`、`model`、`storage` 和 `ui` 分模块。平台描述、图标和消息资源在 `src/main/resources/`；测试位于 `src/test/kotlin/`，并按主代码包路径组织。`docs/` 保存设计文档，`skills/understanding-frames/` 保存 Agent 工作流。`prototype/` 是独立的交互原型，不属于插件源码。

## 构建、测试与本地开发

使用 JDK 21 和仓库自带的 Gradle Wrapper，从项目根目录运行：

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin --offline
```

`test` 使用 JUnit Platform；`buildPlugin` 将安装包写入 `build/distributions/`。`runIde` 启动 IntelliJ IDEA 2026.1.5，`runIdeLatest` 启动开发实例 2026.2.3；`verifyPlugin` 检查 2026.1.5 和 2026.2.3 的兼容性。离线验证要求对应 IDE 已缓存。

## 代码风格与测试

沿用现有 Kotlin 和 Compose 写法，缩进使用 4 个空格、尾随逗号、无制表符。包名用小写，类型名用 `UpperCamelCase`，函数和属性用 `lowerCamelCase`；测试类以 `Test` 结尾。行为变更应在对应包的 `src/test/kotlin/` 中补充或更新测试，先运行针对性测试，再运行完整 `test`。图表和源码导航使用事件、几何和离屏绘制回归；实际界面验收由用户安装新版插件完成。

每个文件围绕一个内聚职责组织。生产 Kotlin 文件超过 500 行、测试 Kotlin 文件超过 800 行时，必须在同次改动中拆分；渲染、交互策略、平台适配和纯几何规则应尽早分开。移动或新增代码时保留适当 KDoc，并同步维护对应测试。

保留未来公开发布能力，优先使用 IntelliJ 公开 API。内部 API 使用不能通过反射或降低检查标准来隐藏。当前图表采用 ELK 布局与 Swing/Java2D 绘制；替换后端必须验证公开入口、兼容性和路径动画等现有能力。

## 提交与拉取请求

提交记录采用带类型和可选范围的简短格式，例如 `feat(ide): ...`、`feat(plugin): ...`、`docs: ...`。提交说明应点明变更内容。拉取请求请描述用户可见的变化和原因，列出已运行的检查；界面改动附截图，并关联相关问题。

## 本地配置

`.idea/`、构建输出和 IntelliJ 沙箱属于本地状态，已由 `.gitignore` 排除。不要提交本机数据库、IDE 配置或凭据。

## Master Understanding：IDEA MCP 连接说明

本项目开发时会同时出现两条内置 IDEA MCP 连接，下表区分主开发 IDE 与插件验收 IDE。调用前先确认目标 IDE 已打开对应项目，始终传入其绝对 `projectPath`；不得为探测连接而自动打开工程或启动 IDE。停用 Index MCP，不作为源码分析或失败回退。

| 连接名          | 连接对象                                                                                           | 主要用途                                                     | 工具入口                            |
|-----------------|----------------------------------------------------------------------------------------------------|--------------------------------------------------------------|-------------------------------------|
| `idea`          | 日常开发使用的 IDEA，当前打开包含本插件工程的工作区                                                | 查找插件源码、分析符号、检查修改后的文件及构建配置                   | `execute_tool` 路由器               |
| `idea_dev_2026` | 由 `Master Understanding IDE` 启动的开发 IDE，加载本插件；其中打开的项目是验收对象，可能随测试改变 | 调用插件自身的 MCP 工具，检查开发 IDE 中的运行行为和测试项目 | `execute_tool` 路由器               |

两条内置连接通过各自的 `execute_tool` 路由器调用。将 `get_understanding_context` 或 `present_understanding` 及参数放入 `command`，把插件验收 IDE 当前打开的项目路径放在外层 `projectPath`；不要将插件工程路径误传给验收 IDE。本插件目前只有这两个 MCP 工具。本工程 Kotlin 源码分析遵循 `idea-mcp-priority`：优先使用 `idea` 的项目搜索、符号分析、语义操作和诊断能力；内置能力不可用或合理修正后仍失败时，用等价的自身工具处理并说明覆盖限制。普通磁盘读取、文本编辑、新增及命令执行使用本机工具；`idea_dev_2026` 用于插件自身工具及验收对象，不能作为开发源码分析的替代连接。

开发默认打开父级 `Agent-dev` 工程。仓库内 `Master Understanding IDE`、`Master Understanding Tests` 和 `Master Understanding Verifications` 三份运行配置的 Gradle 路径都是 `$PROJECT_DIR$/master-understanding`，这是父工程下的预期设置，不为单独打开插件目录而修改。启动插件时选唯一的 `Master Understanding IDE`；不要选旧配置 `Run IDE with Plugin`，它属于 `walkthrough-plugin`，会把没有 Gradle 构建文件的工作区根目录传给 Gradle。单独打开 `master-understanding` 时，使用本目录的 Gradle 命令，或另建 Gradle 工程目录指向本插件 `settings.gradle.kts` 所在目录的配置。

开发 IDE 重新启动后，旧的 stdio MCP 连接可能失效。MCP 重连只恢复通信，不会把新构建的插件代码加载进仍在运行的 IDE。自动化工作不要为验证而启动开发 IDE 或截图；需要时等待用户明确要求。

测试记录保存在开发 IDE 当前打开项目的 `.idea/code-understanding/` 中。写入前确认项目，使用明确标记的测试内容；不要把测试记录误认为插件工程自身的数据。

## 自动化检查

不要自行启动开发 IDE、操作桌面或截图检查界面。完成必要的命令行测试与代码检查即可；需要视觉反馈时使用用户提供的截图，除非用户明确另行要求交互检查。

## 说明
- ![context.md](./docs/context.md) 必读
