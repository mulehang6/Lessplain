# 可编辑教程工作流

教程使用原生 IDE Document 保存 Markdown，标签栏右侧使用原版 Markdown 的编辑、分栏、预览图标，默认预览。用户连续选区后可直接追问或写笔记。Agent 可以改写正文，版本仅防并发丢写。

## 首次创建

1. lessplain_context_read 或 lessplain_context_capture 获取 contextId 和 sourceId。
2. 读源码，围绕用户当前知识缺口组织文字、代码、表格和必要插图。
3. lessplain_tutorial_create 一次提交 title、markdown、diagrams、simulations。整个提交先校验。
4. 核对返回 ID 和 revision，需要展示时 lessplain_reveal。

Markdown 资源引用：

- 源码：[项目中的返回值](lessplain://source/src_return)
- 插图：![事件传递](lessplain://diagram/delivery)
- 模拟：![情境对比](lessplain://simulation/cold_flow)

diagram/simulation 的 ID 只在所属教程内有效。引用必须有对应资源；source ID 必须由插件解析。

## 持续编辑

读取教程后，lessplain_tutorial_edit 使用 expectedRevision 与 edits:[{find,replace}]。每个 find 必须唯一，顺序作用于上一个替换结果，不做模糊替换。

图和模拟用专门工具完整更新，资源版本与正文版本独立。图节点的人工位置按 ID 保留。替换模拟后播放回初始状态。正文删掉引用不会自动删除资源或用户批注。

## 教学模拟

每个资源包括 language、只读 code、scenarios。情境包括 id、label、steps。步骤包括从 1 开始的 highlightLines、简短 caption 和可选 stdout。stdout 是本步新增文本；播放界面按步骤前缀重建输出。

模拟没有解释器，不接收任意用户参数。输出来自 Agent 编排，应核对正确性，并与真实观测区分。纯流程演示省略 stdout。需要项目真实行为证据时，使用已有源码或调试器核实，再编写教材。

## 笔记与追问

用户选区由插件冻结为 UTF-16 源范围与原文快照。笔记、问题草稿和答复分别保存。待答问题只在明确提交后出现。答案更新所属浮窗，不重建正文。正文删除后无法定位的批注保留为失联记录，可由索引找回。

笔记浅黄、追问浅蓝，同时覆盖的文字浅紫。浮窗四个图标用于上一条、下一条、新增和删除；窗口右上角关闭只隐藏窗口，批注索引可重新打开。用户确认删除追问时，该条回答和后续追问一并删除，引用的独立材料保留。

Agent 用 lessplain_annotations_list 读取，再 lessplain_question_answer。需要新产物时先创建，再把 materialId 附在答复里。不要用新教程冒充已答复旧问题。

调用示例见 [protocol.md](protocol.md)。
