# annoy-me 个人试用版交接

## 当前实现

采用一份 `skills/annoy-me/` 目录，安装到 `~/.agents/skills/`，Claude Code 复制或链接到自己的技能目录。参考 Matt Pocock 的 [grill-me 入口](https://github.com/mattpocock/skills/blob/main/skills/productivity/grill-me/SKILL.md) 与 [Codex 配置](https://github.com/mattpocock/skills/blob/main/skills/productivity/grill-me/agents/openai.yaml)，在同一包中保留 `disable-model-invocation: true` 和 `policy.allow_implicit_invocation: false`。

已移除五种宿主导出脚本及其测试，清理本轮生成的 15 个旧导出文件，安装无需生成副本。OpenCode 与 Kilo 暂以技能描述和教学正文限定主动开启，不声称其客户端强制禁止自动调用。具体入口、刷新方式与文档依据见 [宿主说明](../../skills/annoy-me/references/agents.md)。

教学正文保留在 `references/workflow.md`：先调查真实代码，每轮一个主问题，等待用户回答，由用户决定何时进入实现。不固定模型、MCP 前缀或独立会话；工具失败时保留当前任务，纯聊天分支由用户选择。

`skills/lessplain/SKILL.md` 的现名与回复规则修改保留。图后允许提问、反馈和补充，不用文字重复解释已呈现的图，协议和源码证据约束保持有效。本版未集成发行包。

## 验证记录

此次检查通过：YAML 宿主开关、标准技能字段与正文、三个本地文档引用，以及教学正文与旧产物的一致性。bundled 校验器尚不识别 `disable-model-invocation`，因此该布尔字段单独解析检查，标准部分通过内存中排除该扩展字段的副本校验；没有修改源文件或校验器来绕过检查。

此前教学正文经过独立文本模拟，覆盖启动边界、连续问答、提示与补讲、退出、后续任务、图后提问和工具失败。纯聊天选择、参数修正仅重试一次及失败后恢复原会话经过复核；此次简化未修改教学正文。

此前在 JDK 21 下执行 `test buildPlugin verifyPlugin --offline` 成功，插件 356 项测试通过。IDEA 2026.1.5 和 2026.2.3 均返回 Compatible，每个目标仍有既有的 7 项 deprecated API 和 8 项 experimental API 警告。此次仅修改技能配置和文档、删除导出代码，未修改插件生产代码。

旧导出器曾有 10 项测试通过、1 项符号链接权限测试跳过；这些结果属于已移除实现，不代表当前安装方式的客户端验收。

## 待个人试用

五个宿主的原生客户端版本和交互尚未验收。官方文档及源码核对日期为 2026-10-12，安装后仍需记录实际版本，按宿主说明试用主动开启、普通任务不触发、连续问答、基础分支选择、图后提问、失败降级与退出后不重新教学。

尤其观察 OpenCode、Kilo 对同一包的加载与误触发情况，有真实问题后再补适配。不新增学习档案或评分；最后用真实开发任务判断是否帮助用户审查方案。

未自动安装、修改全局配置、启动客户端或提交。已有设计文档、原型文档和技能目录迁移改动保留。
