# 到达节点的路径高亮与双向导航

> 历史记录说明：本文中的 `master-understanding` 是 Lessplain 的旧项目名；当时的路径、安装包和验证记录保留原样。

## 已完成

实现先在 `feat/incoming-path-highlighting` 独立工作树完成，随后按用户要求整合到主工程的 `feat/incoming-path-main-integration` 分支，基于 `f969389fdd050c7058f245170eec1e972e0e9aff`。本轮以本地 Git 提交交付导航代码及其依赖的多图簇、完整路线选择和源码会话功能；开发 IDE 配置沿用主工程，原工作树保留。

- 直接点击节点时，展开所有入口到该点的上游路径；再次点击当前节点也会重新展开。
- 上一步按实际入边、下一步按实际出边判断。没有候选则禁用按钮，单条候选直接移动，多条候选立即弹出路线菜单。
- 逐步导航保留已选择的路线。直接点击汇合点后继续前进，会保留全部未决上游，再追加选中的边；后退到未走过的前驱时展开其上游。
- 平行边按独立边选择，菜单显示起止节点、条件和关系；取消菜单保持当前路线。
- 优先使用能到达该点的 `start` 节点，缺少此类起点时使用上游的自然入口或源强连通分量。包含首次到达目标前的环，自动展开时排除目标出边。
- 工具窗口、追问组合画布、独立图编辑器和源码内联按钮采用同一套路径语义。导航前缀与已选后缀分别保存，重新打开时恢复；旧状态继续读取，损坏的路线回退为所选节点的全部上游。
- 选择、页面销毁、路线状态或图版本变化会使旧菜单回调失效。组合画布与源码之间按所属记录映射节点及边。
- 源码内联按钮优先交给当前画布的导航会话，画布选路期间暂停步进。相同路线快照回传保留正在进行的补全；变化或损坏的快照关闭旧菜单。

自动展开采用有限边集合，不枚举路径，覆盖 `control_flow`、`call`、`data_flow`。逐步浏览限制重复访问次数为 100，不截断合法长非循环路线；`staticPath` 保存有序已选后缀，高亮边集合独立计算。沿用主工程 schema 4，兼容无分组字段的 schema 3 请求，不新增数据库迁移。

主要实现：`core/UpstreamPathResolver.kt`、`core/StaticExplorer.kt`、`diagram/DiagramRouteNavigation.kt`、`diagram/DiagramSourceNavigation.kt`、`diagram/RouteChoicePopup.kt`、`ide/SourceGraphNavigation.kt`。源码路径均位于 `src/main/kotlin/com/master/understanding/` 下。

## 已验证

主工程整合版使用 JDK 21 执行以下检查：

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon
.\gradlew.bat test buildPlugin --no-daemon
.\gradlew.bat verifyPlugin --offline --no-daemon
```

- 针对性导航回归通过，最终全量 232 项通过，失败、错误和跳过均为 0。
- 测试覆盖多入口、汇合、平行条件边、上游环、无自然入口的环、指数数量路径的有限边集合、方向选择、源码映射、状态恢复、取消与过期回调；增加跨记录范围、长非循环路线、画布会话接管及相同快照保留补全的整合回归。画布采用 Swing 事件和离屏检查。
- `buildPlugin` 成功；安装包为 `build/distributions/master-understanding-1.0.0-SNAPSHOT.zip`，23,231,713 字节。
- 最终安装包的 `verifyPlugin --offline` 成功，IDEA 2026.1.5（`IU-261.27258.48`）和 2026.2.3（`IU-262.10968.63`）均判定为 `Compatible`。报告分别位于 `build/reports/pluginVerifier/IU-261.27258.48/report.html` 和 `build/reports/pluginVerifier/IU-262.10968.63/report.html`。
- 兼容报告保留现有的 4 / 7 项弃用 API 和各 8 项实验性 API 提示，涉及工具窗口、标签标题及 SwingPanel 入口。
- 内置 IDEA MCP 诊断没有返回本轮整合文件的错误；工程工作流技能校验及 `git diff --check` 通过，生产 Kotlin 文件未超过 500 行，测试文件未超过 800 行。

安装包 SHA-256：

```text
F73CB3AA175DEEBF502C55349833E61BD6038EFCB9F933E664D39AEC9AE7E8A7
```

独立工作树版本此前通过 187 项全量测试、打包及两版兼容检查，该数字属于整合前版本。

## 代码保存与恢复

在主工程继续使用原开发 IDE 配置。切换分支后，检出 `feat/incoming-path-main-integration` 即可恢复本轮代码；其他工作树也可以从该分支或对应提交创建。仓库未配置远端，本轮交付为本地 Git 提交。

## 仍需用户验收

自动化未启动开发 IDE 或检查实际窗口。安装新版插件后验收：

1. 直接点击汇合节点，确认其所有上游路径高亮；再次点击当前节点应重新展开。
2. 从起点逐步前进、从任意节点前进或后退，确认仅在当前方向有多条候选边时弹出菜单；刚走过的汇合点后退也需要选择。
3. 选择平行边条件、取消菜单、切换节点后确认旧菜单不能覆盖新状态。
4. 在工具窗口、追问画布、独立编辑器及源码内联按钮间切换，并关闭重开，确认路线与当前位置保持一致。
