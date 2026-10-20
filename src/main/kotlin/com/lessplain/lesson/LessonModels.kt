package com.lessplain.lesson

import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph

/** 教材是独立产物，不要求存在图节点；来源仍可追溯到真实源码。 */
data class LessonOrigin(
    val anchor: SourceAnchor,
    val recordId: String? = null,
    val nodeId: String? = null,
    val focus: LessonSourceFocus? = null,
)

/** 有限的原生教材块。代码可以引用实际示例，图沿用现有语义图模型。 */
data class LessonBlock(
    val id: String,
    val kind: String,
    val text: String = "",
    val level: Int = 0,
    val language: String = "kotlin",
    val exampleId: String? = null,
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val graph: UnderstandingGraph? = null,
    val source: SourceAnchor? = null,
    val richText: List<LessonTextRun>? = null,
    val sourceBindings: List<LessonSourceBinding>? = null,
)

/** 样式不占用字符；拼接后的文字是选区、哈希和批注的唯一文本。 */
data class LessonTextRun(val text: String, val style: String = "plain", val href: String? = null)

/** 上下文用于唯一定位，start/end 是上下文内的 UTF-16 字符范围。 */
data class LessonSourceFocus(val anchor: SourceAnchor, val start: Int, val end: Int)

/** 一个教材块可显式关联多个概念，不根据同名词自动绑定。 */
data class LessonSourceBinding(
    val id: String,
    val label: String,
    val focus: LessonSourceFocus,
    val start: Int? = null,
    val end: Int? = null,
)

/** 可运行例子只写入课程自己的目录；依赖使用 Maven 坐标，不借用业务编译输出。 */
data class LessonExample(
    val id: String,
    val fileName: String,
    val code: String,
    val dependencies: List<String> = emptyList(),
)

data class LessonRecord(
    val id: String,
    val revision: Int,
    val title: String,
    val origin: LessonOrigin,
    val blocks: List<LessonBlock> = emptyList(),
    val examples: List<LessonExample> = emptyList(),
    val createdAt: Long,
)

/** 固定问题产生时的具体内容；annotationId 允许在补充回答里继续追问。 */
data class LessonTarget(
    val blockId: String? = null,
    val annotationId: String? = null,
    val start: Int = 0,
    val end: Int = 0,
    val selectedText: String = "",
    val contentVersion: String = "",
    val objectId: String? = null,
    val row: Int? = null,
    val column: Int? = null,
)

/** AI 回答与用户笔记分别保存；生成结果不得替换用户笔记。 */
data class LessonAnnotation(
    val id: String,
    val lessonId: String,
    val target: LessonTarget,
    val question: String = "",
    val note: String = "",
    val contextToken: String? = null,
    val answer: List<LessonBlock> = emptyList(),
    val status: String = "note",
    val createdAt: Long,
)

data class LessonContext(
    val token: String,
    val lessonId: String,
    val revision: Int,
    val question: String,
    val annotationId: String? = null,
    val target: LessonTarget? = null,
    val createdAt: Long,
)

data class LessonBrowse(
    val blockKey: String? = null,
    val offset: Int = 0,
    val expandedAnnotations: Set<String> = emptySet(),
)

/** 一个文件对应一个项目的教材状态；旧理解图的数据库和协议不迁移。 */
data class LessonState(
    val schemaVersion: Int = 1,
    val lessons: List<LessonRecord> = emptyList(),
    val annotations: List<LessonAnnotation> = emptyList(),
    val contexts: List<LessonContext> = emptyList(),
    val browse: Map<String, LessonBrowse> = emptyMap(),
    val receipts: List<LessonReceipt> = emptyList(),
)

data class LessonReceipt(val requestId: String, val fingerprint: String, val lessonId: String)
