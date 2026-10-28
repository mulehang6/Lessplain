# 分支复审与平台 API 清理

范围为 experiment/inline-or-generativeui 相对 main 的提交差异、现有未提交改动及相关生产调用、测试、注册、协议、skill、构建与原型。保留既有用户资料和无关改动，不额外提交 Git，不启动开发 IDE。

## 已修复并纳入回归

| 触发条件 | 修复 | 验证入口 |
| --- | --- | --- |
| 删除来源图后，另一记录仍保留冻结上下文，再用该上下文创建显式子图 | SQLite 写事务内同时检查冻结来源所属记录是否仍存在 | RequestLifetimeTest |
| 工具窗口查看 B 图，编辑器活动图为 A 或已清空 | 在编辑器打开动作读取工具窗口路由 | OpenUnderstandingInEditorActionTest |
| 某个教程写入失败，随后其他文件写入成功 | 按文件保留最新失败快照，成功重写清除该文件错误，flush 重试未完成写入 | TutorialPersistenceRecoveryTest |
| 正文多处替换后进程中断或部分保存失败 | 正文、批注范围和编辑回执统一原子保存 | TutorialPersistenceRecoveryTest、TutorialEditorUndoTest |
| 插图已保存但来源索引写入失败 | 插图、模拟和教程来源索引纳入相同内容快照 | TutorialAtomicStateTest |
| 答复落盘而全局回执失败，再重试旧答复 | 答复及请求回执随批注原子保存；重放旧回执不重写新答案 | TutorialAtomicStateTest、TutorialMcpTest |
| 删除教程后根目录回执清理失败 | 重开时再次净化并写回已删除内容的回执 | TutorialAtomicStateTest、TutorialDeletionTest |
| 批注删除遇到文件写失败 | 先后台严格提交删除快照，再发布删除；失败保持原批注并提示 | TutorialAtomicStateTest、TutorialMcpTest |
| 已确认删除后项目关闭 | 独立串行写队列排空已接收操作，关闭后丢弃界面回调 | ConfirmedWritesTest |
| 正文前方连续插入内容或删除选区 | 修改前建立 Document RangeMarker，刷新后恢复同一原文及阅读位置，删除原文则清空选择 | TutorialPreviewPositionTest |
| 回答引用的图被删除 | 订阅图变更并后台检查记录存在；只更新链接，不替换输入和回答 | TutorialMaterialNavigationTest |
| 编辑器创建、结果更新、教程模式切换或防抖刷新 | 涉及 IDE 模型的延迟回调使用 Application.invokeLater，在平台读写意图域完成 | SourceInsightTrackingTest、TutorialPreviewInteractionTest、TutorialPlatformTest |
| 正文先写入图或模拟引用，资源稍后才保存 | 根据已引用资源的可用性更新占位区域，保留既有模拟组件与播放状态 | TutorialResourceLifecycleTest |
| 点击教程链接所在行的空白处，或拖选链接文字 | 只在实际文字命中且无连续选区时打开链接 | TutorialPreviewInteractionTest |
| 自动结果完成读取前，用户已切走工具窗口 | 自动发布前再次检查活动状态及导航代次，保留未读与待打开状态；显式打开仍可执行 | PanelRestorePlatformTest |
| 已存在教程被相同创建请求命中，而全局回执写入失败 | 复用创建也把完整首次回执写入教程原子快照，后续编辑不改变原请求重放结果 | TutorialMcpDurabilityTest |
| 旧测试仍调用已从界面移除的自动选路入口 | 删除 selectNode、back、focusNode 及仅供旧入口使用的前缀推导；有效覆盖迁至聚焦、同步、显式补全和逐步导航 | IncomingRouteTest、RouteReselectionTest、StaticExplorerTest、QuestionCanvasNavigationTest |

## API 与冗余清理

- 工具窗口移除 Compose/SwingPanel 包装，直接复用历史列表和图组件，搜索文档不会因异步加载而卸载。删除其专属 BackgroundRead composable、QuestionIndexView 包装及 Compose 编译/运行依赖，保留公共 LatestBackgroundRead。
- 工厂与图标签标题提供器只实现实际使用的稳定同步方法，避免 Kotlin 固化无用的弃用/实验默认接口桥接。图虚拟文件名称保存规范化后的完整标题，标签提供器返回短标题，提示沿用平台默认实现。教程虚拟文件名称已经是完整标题，删除重复的教程标题提供器。
- 用普通 Swing 选中背景替代实验 SelectablePanel，保留主题圆角及命中；UiRead 使用稳定线程断言，相关模型任务由平台入口调度。
- Markdown 改用公开 MarkdownParserManager.parseContent；不使用其弃用默认 flavour 字段。平台入口只展开块级 AST，教程资源引用单独扫描并跳过代码跨度和转义。
- 测试迁移到稳定 action event 工厂及带 scheme 的 highlighter 属性读取；移除无用非空断言和测试对内部 UISettings 状态的直接修改，改用真实滚动事件及完成通知。
- 现行 skill 调用统一写完整 lessplain_ 工具名，PERSISTENCE_FAILED 按普通写入失败说明。删除旧模块的覆盖由现行组件测试承接，不保留空壳或停用分支。
- 全库 IDE 静态分析覆盖 363 个生产、测试与资源文件，无超时、跳过或失败。SQL 字符串中的未关联 IDE 数据源提示与编译错误分开处理；表结构由初始化和数据库测试验证。
- 结合生产调用和注册，删除 previousChoices、isEntry、canGoBack、addNodeNote、editRecordTitle、旧整队列 pendingQuestions、getAnswer、focusHalo 及未使用导入。仅测试引用的旧路线入口同步迁移并删除，未因旧断言保留实现。
- 清理后复查生产、测试和插件注册，未发现旧首页、旧 Compose 外壳、旧分层回看、删除确认包装或旧路线别名的调用残留。现行 MCP 注册仍为 15 个工具。

## 验证状态

最终检查执行 clean test buildPlugin verifyPlugin，使用 --offline --no-build-cache --rerun-tasks 强制重编译，未降低内部 API 检查标准。

| 检查 | 最终结果 |
| --- | --- |
| 插件测试 | 146 个测试类，493 项测试，失败 0、错误 0、跳过 0；测试输出没有读锁、空指针或断言错误 |
| IDEA 2026.1.5（261.27258.48） | Compatible；弃用、实验、内部、禁止覆写和其他兼容问题报告均为空 |
| IDEA 2026.2.3（262.10968.63） | Compatible；弃用、实验、内部、禁止覆写和其他兼容问题报告均为空 |
| 原型 pnpm run check | 16 项测试全部通过；TypeScript、Vite 和 portable 打包通过；此后原型源码未修改 |
| 变更与引用检查 | git diff --check 通过；删除项无生产、测试或注册残留；15 项 MCP 工具参数保持不变 |

最终构建日志为 lessplain-audit-final-20261028.log，原型日志为 lessplain-prototype-audit-20261028.log。插件测试报告位于 build/reports/tests/test/index.html，双版本完整报告位于 build/reports/pluginVerifier/。SDK 布局缺失提示及 JVM CDS 提示来自验证环境，未被当作插件 API 问题隐藏或屏蔽。

安装包：build/distributions/Lessplain-0.0.1.zip，24,003,592 字节。SHA-256：880a54537902bde6b0ee86eb7da94158354737ab8bf9ab1b3c38934ec6dfd76c。

最后一次审查修正了复用创建请求的回执恢复，并清除了仅被旧测试保留的自动选路。针对修改再次审查和回归后，未发现新的可确认缺陷或废弃实现。分支复审阶段保留全部未提交改动，没有创建 Git 提交，也没有修改业务工程。

实际主题、输入手感和窗口表现由用户安装验收；自动化使用平台事件、几何及离屏绘制。

## 合并前复核

用户于 2026-10-28 在线运行 verifyPlugin，18:52:58 生成的双版本报告均为 Compatible。安装包 SHA-256 与上述强制重编译产物一致，未发现需要追加修复的兼容问题。

合并前使用 JDK 21 再运行 `test buildPlugin verifyPlugin --offline`，19:27:52 完成验证，整体 BUILD SUCCESSFUL，耗时 7 分 21 秒。测试及打包任务复用最新输出，测试报告仍为 146 类、493 项，失败、错误及跳过均为 0；报告中没有线程访问断言、NullPointerException 或 AssertionError。双版本重新验证均为 Compatible，没有额外 API 问题报告，依赖下载量为 0 B。IDE 布局缺失 JAR 提示来自验证环境。

`git diff --check` 通过；生产和测试 Kotlin 文件均符合行数限制。已刷新 origin，本地主分支与 origin/main 一致，且为实验分支祖先。既有改动按界面与导航、教程持久化、Markdown 公开 API 与构建依赖、文档四组提交，保留各组提交后快进合并到本地 main。远端推送和实际界面验收另行进行。
