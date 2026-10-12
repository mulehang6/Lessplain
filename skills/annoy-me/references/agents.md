# 安装与个人试用

所有宿主使用同一份 `annoy-me/` 目录，教学正文只维护在 `references/workflow.md`。不需要导出脚本或按宿主生成不同副本。

## 安装与调用

将仓库的 `skills/annoy-me/` 复制到 `~/.agents/skills/annoy-me/`，或在那里创建指向源目录的目录链接。Claude Code 使用 `~/.claude/skills/annoy-me/`，可以复制或链接同一个源目录。更新时同步整个目录，保留 `agents/` 和 `references/`。

同时安装并连接 Lessplain 插件，将 `skills/lessplain/` 放入相应技能目录。迁移已有 `understanding-frames` 时先保留个人修改，再改用 `lessplain`；避免同时安装两个不同名称的版本。

| 宿主 | 入口 | 调用控制与刷新 |
| --- | --- | --- |
| Codex | `$annoy-me` 加当前任务 | `agents/openai.yaml` 禁止隐式调用；自动发现，未更新时重开会话或重启客户端 |
| Claude Code | `/annoy-me` 加当前任务 | `disable-model-invocation: true` 禁止模型调用，保留用户调用；支持技能热刷新，未出现时重开会话 |
| Pi | `/skill:annoy-me` 加当前任务 | 同一 frontmatter 开关禁止模型自动选择；`/reload` 刷新 |
| OpenCode | 明确要求“用 annoy-me 学习当前任务” | 本版未添加 OpenCode 的 slash 或 autoinvoke 元数据，依靠描述与教学正文限定启动；安装后重开会话 |
| Kilo 新平台 | `/annoy-me` 加当前任务 | 技能本身提供 slash 入口；本版依靠描述与教学正文限定启动，`/reload` 刷新 |

本版在同一个技能包中保留 Codex 与 Claude/Pi 的手动调用配置。其他宿主忽略或不支持的开关，不能视为强制禁止自动调用。先个人试用，实际出现误触发或加载问题时再补对应宿主适配。不生成 Kilo 独立 command，也不需要本版提供同名 skill 的 deny 配置。

## 文档支持与实际验收

文档核对日期为 2026-10-12。目录与调用方式有官方依据，原生客户端的实际版本和交互仍未验收。

| 宿主 | 文档依据 | 原生客户端已验收版本 |
| --- | --- | --- |
| Codex | [共享目录与调用配置](https://learn.chatgpt.com/docs/build-skills) | 尚未验收 |
| Claude Code | [技能目录与调用控制](https://code.claude.com/docs/en/skills) | 尚未验收 |
| Pi | [技能](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md)、[MCP](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/mcp.md) | 尚未验收 |
| OpenCode | [共享目录与宿主元数据](https://opencode.ai/docs/skills/) | 尚未验收 |
| Kilo | [共享目录与技能入口](https://kilo.ai/docs/customize/skills) | 尚未验收 |

Pi 文档已有内置 stdio 和 HTTP MCP 支持，接入时检查实际版本与工具可用性，不预设需要第三方桥接。其他宿主同样需要确认 Lessplain 连接到目标项目并暴露工具，教学正文不固定 MCP 前缀。

## 试用观察

用真实任务记录宿主版本与结果：普通开发和技能设计讨论是否保持原流程；主动开启后能否连续问答、得到提示与补讲、选择基础分支；图后能否继续提问；工具失败是否等待用户选择；结束教学后下一项任务是否保持普通开发流程。

没有用户回答时停在问题处，不模拟教学。最后判断这些问答是否帮助用户解释现有行为、预测方案影响和发现风险，再决定是否维护或分发。
