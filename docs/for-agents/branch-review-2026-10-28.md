# 2026-10-28 分支复审与验证

范围为实验分支相对 main 的改动及相关生产调用、测试、注册、协议、skill、构建与原型。当前实现说明见[主设计](code-understanding-design-v0.5.md)、[教程](editable-tutorials.md)和[平台生命周期](platform-integration-threading.md)。

## 主要修复与回归

| 触发条件 | 修复后的行为 | 回归入口 |
| --- | --- | --- |
| 来源图已删除，其他记录仍保留冻结上下文 | SQLite 写事务复核冻结来源所属记录仍存在 | RequestLifetimeTest |
| 工具窗口与编辑器活动图不同 | 在编辑器打开动作读取工具窗口路由；自动结果发布再次检查活动状态与导航代次 | OpenUnderstandingInEditorActionTest、PanelRestorePlatformTest |
| 单文件保存失败，随后其他文件保存成功 | 按文件保留最新失败写入，flush 重试；成功重写只清除对应文件错误 | TutorialPersistenceRecoveryTest |
| 正文多处替换、资源或全局回执保存中断 | 正文、资源、批注范围和所属回执作为同一原子快照保存 | TutorialAtomicStateTest、TutorialEditorUndoTest |
| 答复重试、复用创建请求或删除后重开 | 首次回执随内容保存，旧请求不覆盖新答案，重开再次净化已删除回执 | TutorialMcpDurabilityTest、TutorialAtomicStateTest |
| 删除批注写入失败，或确认后关闭项目 | 先严格提交删除再发布界面，失败保留批注；独立队列排空已接收操作 | TutorialAtomicStateTest、ConfirmedWritesTest |
| 连续编辑后选择或阅读位置漂移 | Document 修改前建立范围标记，跟踪同一原文；原文删除后清空选择 | TutorialPreviewPositionTest |
| 图引用失效、资源后到或链接误命中 | 只更新失效入口及相关占位，不打断输入或播放；仅实际文字命中且无连续选区时导航 | TutorialMaterialNavigationTest、TutorialResourceLifecycleTest、TutorialPreviewInteractionTest |

## 平台与冗余清理

- 工具窗口直接装配原生 Swing，移除 Compose/Jewel 外壳及编译运行依赖。历史搜索框不随异步加载重建。
- 工具窗口工厂和图标签提供器仅实现稳定入口，沿用平台默认行为；删除重复教程标签提供器和实验列表选中组件。
- Markdown 块解析使用公开 MarkdownParserManager.parseContent；资源扫描单独保留精确范围，跳过代码跨度及转义。
- 涉及 IDE 模型的延迟回调使用平台调度入口，测试改用稳定 action event 与 highlighter API。
- 删除无生产调用的旧路线入口、欢迎页、整层回看及其他辅助实现，覆盖迁至现行组件，不保留停用分支。
- 现行 MCP 保持 15 个结构化工具，未通过反射或降低检查标准隐藏内部 API。

## 验证基线

使用 JDK 21 强制执行 `clean test buildPlugin verifyPlugin --offline --no-build-cache --rerun-tasks`，随后用户在线验证和合并前离线复核均成功。

| 检查 | 2026-10-28 结果 |
| --- | --- |
| 插件测试 | 146 类、493 项，失败、错误和跳过均为 0；输出没有线程访问断言、空指针或断言错误 |
| IDEA 2026.1.5，261.27258.48 | Compatible，无弃用、实验、内部、禁止覆写或其他兼容问题报告 |
| IDEA 2026.2.3，262.10968.63 | Compatible，无弃用、实验、内部、禁止覆写或其他兼容问题报告 |
| 原型检查 | 16 项测试、TypeScript、Vite 与 portable 打包通过，随后原型源码未修改 |
| 静态分析 | 覆盖 363 个生产、测试及资源文件，无超时、跳过或失败；SQL 数据源提示由数据库测试另行核对 |
| 差异与结构 | git diff --check 通过，Kotlin 文件符合行数限制，删除项无有效引用残留 |

在线验证于 18:52:58 生成报告。合并前再次运行 `test buildPlugin verifyPlugin --offline`，19:27:52 完成验证，整体 BUILD SUCCESSFUL；测试与打包复用最新输出，双版本重新验证均为 Compatible，依赖下载为 0 B。IDE 布局缺失 JAR 与 JVM CDS 提示来自验证环境。

当日产物为 `build/distributions/Lessplain-0.0.1.zip`，24,003,592 字节，SHA-256 为 `880a54537902bde6b0ee86eb7da94158354737ab8bf9ab1b3c38934ec6dfd76c`。测试报告位于 `build/reports/tests/test/`，兼容报告位于 `build/reports/pluginVerifier/`；路径内容会随之后构建更新。

自动化没有启动开发 IDE、操作桌面或截图。实际主题、输入法、窗口及跨重启体验仍需用户安装验收，操作清单见[教程](editable-tutorials.md)和[导航](navigation-and-deletion-2026-10-27.md)。
