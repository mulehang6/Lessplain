# 空闲画布导致 renderer OOM

日期：2026-10-18。

## 现场证据

21:08:59，浏览器 1 报告 `TS_PROCESS_CRASHED`，退出码 `-36861`；21:09:07 又触发失联快照。两份快照已经由内部模式保存到沙盒日志目录的 `lessplain-diagnostics/`。当时 EDT 在 `EventQueue.getNextEvent` 空闲等待，JVM 和 CEF 主进程仍存活，renderer 已退出。

本次原生日志刷新后，`jcef_chromium_19140.log` 明确记录：

```text
[33700:20920:1018/210859.927:ERROR:third_party\blink\renderer\bindings\core\v8\v8_initializer.cc:916] V8 javascript OOM (Ineffective mark-compacts near heap limit).
```

此前 20:55:57 和 20:17:37 的原生日志也有相同 OOM。退出码是崩溃处理过程中的结果，单看它无法解释最初原因；本轮原生日志与前端复现补全了这条证据。

## 前端复现与根因

使用真实 tag 规范载荷，在无服务器离屏浏览器中保持页面空闲，分别开启和关闭内部诊断。DOM 始终为 361 个节点、布局计数不再变化，JavaScript 却持续执行并分配内存。每 20 秒强制 GC 后，约一分钟的保留堆仍由约 30 MB 增至 450 MB；关闭诊断同样复现。日志见本地 `build/heap-profile.log`。

CPU 与保留分配采样映射到 `GlobalCanvasScene`、React Effect 及 WorkspaceProvider。约 20 秒的分配采样中，画布组件本身保留约 154 MB，更新 layout Effect 的路径又保留约 114 MB。采样过程未操作沙盒、未依赖 resize，也未收到新的 Agent 回答。

根因是控制器与其 context 之间的反馈和引用链：

1. GlobalCanvas 根据 `stepFrom` 等回调生成 controller。
2. 发布 controller 的 Effect 调用 `WorkspaceProvider.refreshController`，更新 context。
3. `stepFrom` 原来依赖并捕获整个 workspace 对象；context 改变使它重建。
4. 新回调又使 controller 重建，重复发布，页面在空闲状态也持续渲染。
5. 新回调捕获的旧 workspace 内含上一份 controller，形成不断延长的保留链，GC 无法回收，最终触发 V8 堆耗尽。

这与 [React 官方对 Effect 循环与不稳定依赖的说明](https://react.dev/reference/react/useEffect#my-effect-keeps-re-running-in-an-infinite-cycle) 一致；本轮修复依据仍是本项目的运行采样和实际依赖链。

## 修复及验证

`stepFrom` 只读取 `selectCluster`，因此改为单独依赖并捕获该稳定动作，不再捕获整个 workspace。保留真实选中、步骤导航和控制器刷新，不通过关掉功能、增加节流或放宽内存限制隐藏问题。

同一离屏页面修正后，20 秒末强制 GC 的 JavaScript 堆约 6.9 MB；初始化后停留在约 7.5 MB，ScriptDuration 保持约 0.015 秒，DOM 和事件监听器数量稳定。见本地 `build/heap-fixed-profile.log`。与修正前的持续增长存在明确对照。

随后重新完成一分钟双组对照：关闭诊断时保留堆从 6,872,788 字节到 6,872,788 字节，ScriptDuration 不增长；开启诊断时末值为 6,953,568 字节，ScriptDuration 总计约 0.025 秒。两组均为 361 个 DOM 节点，强制 GC 后分别保持 1348/1350 个监听器，没有页面错误。见本地 `build/heap-fixed-minute.log`。

新增 `browser-idle-check.ts`，对启用/关闭诊断的真实 tag 页面检查空闲期间的保留堆增长、脚本 CPU 和事件监听器；已纳入完整浏览器检查。新增回归先单独通过，再执行工程全量验收。

最终前端 65 项、JVM 148 项、生产构建、完整离屏浏览器、`buildPlugin` 和 `verifyPlugin --offline` 均通过。两版 IDEA 均 Compatible，仍各有平台默认 ToolWindowFactory 方法的 4 项弃用和 6 项实验性提示。安装包摘要见交付记录。

修复版已通过 MCP 启动，并在原生沙盒打开既有 tag 页面，完成折叠往返及跨过 800px 的窗口调整。随后约 145 秒的只读采样中 renderer 持续存活，累计 CPU 约 1.58 秒，私有内存约 54–73 MiB；未出现持续分配至 OOM 的旧趋势。原计划六分钟观察按用户请求提前结束，已停止 Computer Use 和采样辅助任务、移除 Agent 日志点，保留新版沙盒运行。更长时间的挂机验收由用户后续完成，不能把这段短时观察写成六分钟验证。

原始故障快照、CPU/分配采样和临时 profiling 脚本均保留在本地忽略目录，不提交日志、页面数据或本机路径。未修改 GPU、remote、OSR、JVM 堆上限或系统设置。
