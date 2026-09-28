# Master Understanding：IDEA MCP 连接说明

本项目开发时会同时出现两条 IDEA MCP 连接。调用前先确认目标 IDE 打开的项目，并始终传入对应的 `projectPath`；不要仅凭工具名称推断当前项目。

| 连接名          | 连接对象                                                                                           | 主要用途                                                     | 工具入口                            |
|-----------------|----------------------------------------------------------------------------------------------------|--------------------------------------------------------------|-------------------------------------|
| `idea`          | 日常开发使用的 IDEA，当前打开包含本插件工程的工作区                                                | 查找插件源码、分析符号、检查修改后的文件及构建配置           | `execute_tool` 路由器               |
| `idea_dev_2026` | 由 `Master Understanding IDE` 启动的开发 IDE，加载本插件；其中打开的项目是验收对象，可能随测试改变 | 调用插件自身的 MCP 工具，检查开发 IDE 中的运行行为和测试项目 | 直接暴露 IDE 工具及本插件的三个工具 |

本插件的三个工具是 `get_understanding_context`、`present_understanding` 和 `append_understanding_answer`。要验证它们是否真的能在开发 IDE 中读写记录，应使用 `idea_dev_2026`。要检查本工程的 Kotlin 源码，应优先使用 `idea` 的项目搜索、符号分析和检查能力。普通文件读取、编辑、新增和命令执行仍使用本机工具，遵循上级 `AGENTS.md`。

在 `Agent-dev` 工作区启动插件时，选唯一的 `Master Understanding IDE` 运行配置；不要选同名旧配置 `Run IDE with Plugin`，它属于 `walkthrough-plugin`，会把没有 Gradle 构建文件的工作区根目录传给 Gradle。若单独打开 `master-understanding`，使用 `Master Understanding IDE (standalone)`。两个配置的 Gradle 工程目录都必须指向本插件的 `settings.gradle.kts` 所在目录。

开发 IDE 重新启动后，旧的 stdio MCP 连接可能失效。可在 Codex 中重启 `idea_dev_2026` 连接，再直接调用一个插件工具验证；无需因此重启整个聊天环境。MCP 重连只恢复通信，不会把新构建的插件代码加载进仍在运行的 IDE。修改插件后，先构建，再重新启动 `Master Understanding IDE`，最后重连并复验界面与工具行为。

测试记录保存在开发 IDE 当前打开项目的 `.idea/code-understanding/` 中。写入前确认项目，使用明确标记的测试内容；不要把测试记录误认为插件工程自身的数据。
