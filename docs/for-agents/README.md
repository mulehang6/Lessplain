# Agent 参考文档

本目录主要供维护 Lessplain 的 Agent 按任务查阅，包含设计、实现约束和维护记录。产品背景与用户偏好保留在 [context.md](../context.md)，使用说明位于项目 README。

使用方法见[项目 README](../../README.md)，开发规则见 [AGENTS.md](../../AGENTS.md)，工具参数见 [MCP 协议](../../skills/lessplain/references/protocol.md)。

## 设计与实现

| 文档 | 内容 |
| --- | --- |
| [产品背景](../context.md) | 产品方向及用户偏好，保留原文 |
| [主设计](code-understanding-design-v0.5.md) | 当前产品约束、已实现范围和后续能力 |
| [教程](editable-tutorials.md) | 编辑、预览、批注、教学模拟、持久化及安装验收 |
| [源码锚点](anchor-validation.md) | 引用校验、旧位置回退、边依据与定位边界 |
| [线程与生命周期](platform-integration-threading.md) | 后台读写、延迟回调、取消、界面加载和释放 |
| [图布局基准](graph-layout-benchmark.md) | 复现命令、采样方法及有日期的历史测量 |

## 维护记录

| 文档 | 内容 |
| --- | --- |
| [2026-10-26 复审](review-2026-10-26.md) | 来源、幂等写入与生命周期的主要修复依据 |
| [导航与删除](navigation-and-deletion-2026-10-27.md) | 画布恢复、单条问题回看及删除保护 |
| [历史搜索](history-search-2026-10-27.md) | 输入框保留、中文合成、分页与未读入口 |
| [源码提问框定位](source-question-position-2026-10-27.md) | 选区坐标、屏幕边界与首次定位规则 |
| [2026-10-28 复审](branch-review-2026-10-28.md) | 原子保存、公开 API 清理与最近验证基线 |

日期记录只保留修复依据与验证结论，当前行为以设计和实现说明为准。早期计划、旧协议和临时交接过程可从 Git 历史查阅。
