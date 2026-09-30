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

本项目开发时会同时出现两条 IDEA MCP 连接。调用前先确认目标 IDE 打开的项目，并始终传入对应的 `projectPath`；不要仅凭工具名称推断当前项目。

| 连接名          | 连接对象                                                                                           | 主要用途                                                     | 工具入口                            |
|-----------------|----------------------------------------------------------------------------------------------------|--------------------------------------------------------------|-------------------------------------|
| `idea`          | 日常开发使用的 IDEA，当前打开包含本插件工程的工作区                                                | 查找插件源码、分析符号、检查修改后的文件及构建配置           | `execute_tool` 路由器               |
| `idea_dev_2026` | 由 `Master Understanding IDE` 启动的开发 IDE，加载本插件；其中打开的项目是验收对象，可能随测试改变 | 调用插件自身的 MCP 工具，检查开发 IDE 中的运行行为和测试项目 | `execute_tool` 路由器               |

两条连接现在都通过各自的 `execute_tool` 路由器调用。将 `get_understanding_context` 或 `present_understanding` 及参数放入 `command`，把开发 IDE 当前打开的项目路径放在外层 `projectPath`；不要将插件工程路径误传给开发 IDE。本插件目前只有这两个 MCP 工具。要检查本工程的 Kotlin 源码，应优先使用 `idea` 的项目搜索、符号分析和检查能力。普通文件读取、编辑、新增和命令执行仍使用本机工具，遵循 `AGENTS.md` 及上级项目规则。

在 `Agent-dev` 工作区启动插件时，选唯一的 `Master Understanding IDE` 运行配置；不要选同名旧配置 `Run IDE with Plugin`，它属于 `walkthrough-plugin`，会把没有 Gradle 构建文件的工作区根目录传给 Gradle。若单独打开 `master-understanding`，使用 `Master Understanding IDE (standalone)`。两个配置的 Gradle 工程目录都必须指向本插件的 `settings.gradle.kts` 所在目录。

开发 IDE 重新启动后，旧的 stdio MCP 连接可能失效。MCP 重连只恢复通信，不会把新构建的插件代码加载进仍在运行的 IDE。自动化工作不要为验证而启动开发 IDE 或截图；需要时等待用户明确要求。

测试记录保存在开发 IDE 当前打开项目的 `.idea/code-understanding/` 中。写入前确认项目，使用明确标记的测试内容；不要把测试记录误认为插件工程自身的数据。

## 自动化检查

不要自行启动开发 IDE、操作桌面或截图检查界面。完成必要的命令行测试与代码检查即可；需要视觉反馈时使用用户提供的截图，除非用户明确另行要求交互检查。

## 说明
- ![context.md](./docs/context.md) 必读
