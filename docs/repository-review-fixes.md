# 全库审查问题修复记录

本轮修复基于 `6278bb9` 的全库审查，工作分支为 `experiment/inline-or-generativeui`。15 个 MCP 工具的名称与参数保持不变。

## 已修复

| 审查项 | 修复后的行为 | 主要回归 |
| --- | --- | --- |
| 图刷新丢草稿和返回层级 | 虚拟文件按逻辑画布复用，只替换依赖改动记录的内部画布。草稿按图层和宿主保留；刷新前保存视角、路径及节点位置。 | `DiagramRefreshPlatformTest` |
| 源码提示使用旧偏移 | 提示目标由 Document 范围跟随编辑；分栏和重开前检查原文，高亮绘制读取当前范围。 | `SourceInsightTrackingTest` |
| 回答与取消、编辑及重复提交竞争 | 写事务在读取前取得写入权；同连接检查问题和活动回答，再查重、插入并更新状态。 | `UnderstandingMaterialAnswersTest` |
| 准备子图期间父图或来源删除 | 提交时在同一事务检查父记录、冻结来源、项目身份和问题来源；失败回滚，不留下图或回执。 | `UnderstandingRecordMutationsTest` |
| 最近 500 条截断导致漏项 | MCP 使用数据库过滤后的分页查询，不受 UI 最近记录上限影响；只导入本页来源。 | `TutorialMcpPaginationTest` |
| 修订重试被外层版本检查拦截 | 核心请求回执先重放；真实版本冲突返回字段位置和当前版本。 | `TutorialMcpDurabilityTest` |
| 成功读取返回尚未保存的 ID | 创建冻结来源或上下文的读取，在响应前等待落盘。写工具的成功回执也等待保存；反馈消费仍独立使用 SQLite 原子事务。 | `TutorialMcpDurabilityTest`、`TutorialMcpTest` |

## 已清理

- 删除无生产调用的图遍历辅助函数及其专属断言；保留分叉识别覆盖。
- 删除旧锚点响应转换函数；校验断言改为检查实际保存的锚点、依赖和行号。
- 删除未调用的历史切换方法及其专用状态。
- 删除模板消息类、属性资源和插件注册，同步更新源码关联说明。
- 对生产代码、测试及当前文档入口检查，未留下上述删除项的引用。

用户保存的教程、笔记及历史文件没有删除；业务工程、构建和排除规则没有改动。

## 已验证

2026-10-22 使用仓库规定的 JDK 21 运行：

```powershell
.\gradlew.bat test buildPlugin verifyPlugin --offline
```

- 完整测试 **411 项**，失败、错误和跳过均为 0；本轮新增 13 项回归。
- `buildPlugin` 成功，安装包为 `build/distributions/Lessplain-0.0.1.zip`。
- IDEA **2026.1.5**（IU-261.27258.48）与 **2026.2.3**（IU-262.10968.63）均为 Compatible，无内部 API 违规。保留原有的弃用和实验 API 提示：前者 4/12 项，后者 11/12 项。
- 变更 Kotlin 文件符合生产 500 行、测试 800 行限制，`git diff --check` 通过。
- 测试、打包与兼容日志：`build/fixall-delivery-final.log`。

安装包 SHA-256：`170A256E639629E1C003651A847D3073C528CCB9FE50FE69FDA1676BF19C2995`。

## 仍需安装验收

自动化没有启动开发 IDE、操作桌面或截图。平台测试使用真实 Document、Editor 和插件编辑器宿主；实际安装后仍需验收多标签页改名、草稿恢复、分栏源码提示和原生标签页标题更新。
