---
name: lessplain
description: 当用户明确要求使用 Lessplain 或要求可视化代码理解时，使用插件的结构化 MCP 工具保存理解图和可编辑教程。
---

# Lessplain

先冻结问题来源，再读源码。根据问题选择理解图或教程，产物保存成功后才说明已保存。工具参数以实际 schema 为准，对象和数组直接传递。

先读用户明确表达的背景与讲解偏好；上下文若返回偏好记录，一并采用，当前要求优先。背景未知时用日常语言给出容易开始的入口，不要求用户每次自评水平。项目对象首次出现时交代来源、职责和当前用途，再解释必要术语。保留源码名称以便对照；普通判断与见名知意的调用简述作用，被调函数内部只在当前问题需要时展开。提问过或阅读过不代表水平低或已经掌握。

## 组织首次阅读

用户说“带我看看”或“这块看不懂”即可开始，在给定范围内组织阅读。面对完整流程或陌生主题，按[教程工作流](references/lessons.md#分遍阅读与节点细讲)提前准备几遍可独立停下的阅读：先走通一个具体例子，再加入工具、分支或失败路径。每遍交代范围和停点，保留尚未展开的轮廓，按理解需要决定遍数。先组织必要铺垫，不等用户逐个发现该问什么。

## 开始

- 用户复制的载荷含 contextId：调用 lessplain_context_read。
- 从当前源码或图节点开始：调用 lessplain_context_capture，传用户问题。
- 需要其他源码：调用 lessplain_source_resolve，传项目相对路径、唯一上下文及关注文本，保留返回的 sourceId。
- 需要已有材料：lessplain_materials_list 搜索摘要，再 lessplain_item_read 读取正文或资源。
- 工具不可见时说明缺少哪个工具。项目路径使用用户正在理解的业务工程。

ID 与 revision 均从工具返回值取得。插件负责偏移、文件版本、幂等和布局。Agent 不提供坐标、请求 UUID 或文件哈希。

## 理解图

关系问题画图，单个值或名称可直接回答。每图通常不超过 8 个节点，范围大时将子流程收为入口节点。节点标签说明做什么或得到什么，不复述代码。

每个源码节点用 sourceId 引用它自己负责的代码范围。source_resolve 的 context 用于唯一定位，focus 对应实际解释的字段、表达式或代码块；简短标签与范围精确对应，整份函数只在解释整个函数时使用。控制流分支写 condition，其他关系可为 call、data_flow、dependency、structure。边的 label 是简短关系说明，coverage 放完整依据。推断关系标 inferred=true 并提供 coverage。节点 flowRoles 可为 start/end，结束结果可用 endOutcome=success/failure。图不能证明真实运行发生。

图给出主线后，为理解门槛高的节点预先准备教程式细讲，按[节点细讲规则](references/lessons.md#分遍阅读与节点细讲)组织。必要性取决于陌生对象、语法和隐藏前提，按已知读者背景判断，不能用代码行数代替。先保存教程，将返回 ID 放进节点的 tutorialId，再创建或修订图；已有 tutorialId 时直接读改该教程。细讲不能强迫读者逐步走完才能继续看主节点。

调用 lessplain_graph_create，parentId 省略为根图，追问图使用来源图 ID 作为 parentId。lessplain_graph_revise 保留节点及边 ID、顺序、端点、关系类型；需要改结构时新建图。根图可有完整、互斥、单层 clusters，每组用 id、label、nodeIds。

## 教程

用户要学习概念、比较情境、修改学习文档时，走[教程工作流](references/lessons.md)。正常组织文字、代码、表格和必要的图，不用图节点代替完整解释。

lessplain_tutorial_create 一次提交 Markdown、插图和模拟。教材可以由用户和 Agent 修改。修改前 lessplain_item_read 获取当前 revision，lessplain_tutorial_edit 使用唯一文本替换。资源单独用 lessplain_diagram_save 或 lessplain_simulation_save 保存，新增 expectedRevision=0。

模拟是预设教学情境，不是真实执行。只有有助于理解时才加入；不提供运行脚本、任意参数计算或图与步骤联动。

## 回答已有问题

1. lessplain_annotations_list(kind=question,status=pending) 批量读取已提交问题及冻结来源。
2. 每项分别核实源码。对既有细讲的补问，先读原教程并整合修订相关段落；真正独立的新问题才按需创建材料。
3. lessplain_question_answer 按 questionId 明确完成，markdown 为回答，可附 materialId。
4. 复查待答列表。创建产物本身不会完成问题。

笔记和未提交草稿不进入待答队列。答复不依赖当前标签，不能改写用户笔记。问题归属和当时的代码以返回的冻结上下文为准；来源缺失时明确说明，不能用当前选区补造历史。用户可从回答分组标题旁的问号气泡回看该回答对应的问题和冻结代码，无需 Agent 操作浮窗。

用户可在界面确认删除整条问答及后续追问；独立教程和图保留。lessplain_question_answer 返回 NOT_FOUND 时重新读取待答列表，不重建已删除问题。

用户明确把某些图节点注释指定为缺陷反馈时，先读取，再用 lessplain_feedback_consume(ids) 消费这些 ID。图注释不按文字自动分类；kind=feedback 返回可供确认的节点反馈候选。普通读取不会删除内容。

## 展示与错误

需要展示时才调用 lessplain_reveal。它可打开产物、批注，或完整选中精确源码范围。保存和展示是两步。

返回 ok=false 时按 error.code、field 修正。版本冲突重新读取；原文重复就扩大 find 的上下文；源码歧义就收窄或扩大定位上下文。相同写入参数可安全重试。不要去掉来源或版本来绕过错误。

完整参数示例见[工具协议](references/protocol.md)。
