package com.lessplain.lesson

import com.lessplain.storage.sha256
import java.util.UUID

/** 冻结来源、验证答复并事务式保存；不读取活动编辑器，也不调用模型或执行代码。 */
class LessonEngine(
    private val workspace: LessonWorkspace,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readExample: (LessonRecord, LessonExample) -> String = workspace::exampleText,
) {
    private fun id(): String = UUID.randomUUID().toString()

    @Synchronized
    fun start(origin: LessonOrigin, title: String, question: String, token: String = "lesson:${id()}"): LessonContext {
        require(question.isNotBlank()) { "学习问题不能为空" }
        workspace.snapshot.contexts.firstOrNull { it.token == token }?.let {
            check(it.question == question && workspace.lesson(it.lessonId).origin == origin) { "固定来源已被不同问题使用" }
            return it
        }
        val record = LessonRecord(id(), 0, title.ifBlank { origin.anchor.text.take(60) }, origin, createdAt = clock())
        validateLesson(record, allowDraft = true)
        val context = LessonContext(token, record.id, 0, question, createdAt = clock())
        workspace.update { it.copy(lessons = it.lessons + record, contexts = it.contexts + context) }
        return context
    }

    fun context(token: String): LessonContext = workspace.snapshot.contexts.firstOrNull { it.token == token }
        ?: error("教材来源 token 不存在，请重新复制问题")

    @Synchronized
    fun addAnnotation(lessonId: String, target: LessonTarget, question: String = "", note: String = ""): LessonAnnotation {
        require(question.isNotBlank() || note.isNotBlank()) { "问题或笔记不能为空" }
        val record = workspace.lesson(lessonId)
        check(targetIsCurrent(record, target)) { "所选内容已改变，请重新选择后提问" }
        val annotationId = id()
        val context = question.takeIf(String::isNotBlank)?.let {
            LessonContext("lesson:${id()}", lessonId, record.revision, it, annotationId, target, clock())
        }
        val annotation = LessonAnnotation(
            annotationId, lessonId, target, question, note, context?.token,
            status = if (context == null) "note" else "pending", createdAt = clock(),
        )
        workspace.update {
            it.copy(annotations = it.annotations + annotation, contexts = it.contexts + listOfNotNull(context))
        }
        return annotation
    }

    @Synchronized
    fun updateNote(annotationId: String, note: String, stillCurrent: () -> Boolean = { true }) {
        workspace.update { state ->
            if (!stillCurrent()) return@update state
            check(state.annotations.any { it.id == annotationId }) { "笔记不存在" }
            state.copy(annotations = state.annotations.map { if (it.id == annotationId) it.copy(note = note) else it })
        }
    }

    /** 多个并行问题分别以自己的内容快照校验，不能依赖答复顺序或当前 tab。 */
    @Synchronized
    fun present(
        requestId: String,
        token: String,
        title: String,
        blocks: List<LessonBlock>,
        examples: List<LessonExample>,
        annotationReply: Boolean,
        question: String? = null,
    ): LessonRecord {
        require(requestId.isNotBlank()) { "requestId 不能为空" }
        val blockIdentity = if (blocks.all { it.richText == null && it.sourceBindings == null }) blocks.joinToString(prefix = "[", postfix = "]") {
            // 延续第一版持久请求的格式，新增可选字段不能让旧请求的重试失效。
            "LessonBlock(id=${it.id}, kind=${it.kind}, text=${it.text}, level=${it.level}, language=${it.language}, " +
                "exampleId=${it.exampleId}, columns=${it.columns}, rows=${it.rows}, graph=${it.graph}, source=${it.source})"
        } else blocks.toString()
        val fingerprint = sha256("$token|$title|$blockIdentity|$examples|$annotationReply|$question")
        workspace.snapshot.receipts.firstOrNull { it.requestId == requestId }?.let {
            check(it.fingerprint == fingerprint) { "requestId 已被不同内容使用" }
            return workspace.lesson(it.lessonId)
        }
        val context = context(token)
        require(question == null || question == context.question) { "问题原文与冻结的问题不一致" }
        val original = workspace.lesson(context.lessonId)
        check(annotationReply == (context.annotationId != null)) { "教材与补充回答的目标类型不匹配" }
        val record: LessonRecord
        var replied: LessonAnnotation? = null
        if (annotationReply) {
            val annotation = workspace.snapshot.annotations.firstOrNull { it.id == context.annotationId }
                ?: error("待答问题已不存在")
            check(annotation.status == "pending") { "该问题已经回答" }
            check(targetIsCurrent(original, annotation.target)) {
                "问题对应的教材内容已变化；原问题与笔记仍保留，请重新选择内容"
            }
            require(examples.none { fresh -> original.examples.any { it.id == fresh.id } }) { "补充示例不能覆盖已有示例" }
            record = original.copy(examples = original.examples + examples)
            require(blocks.isNotEmpty()) { "补充回答不能为空" }
            validateLessonBlocks(blocks, record.examples.map { it.id }.toSet())
            replied = annotation.copy(answer = blocks, status = "answered")
        } else {
            check(original.revision == context.revision && original.blocks.isEmpty()) {
                "原教程已经生成；请选中具体内容追加解释，不覆盖已有材料和笔记"
            }
            record = original.copy(revision = 1, title = title.ifBlank { original.title }, blocks = blocks, examples = examples)
        }
        validateLesson(record)
        workspace.materializeExamples(record)
        workspace.update { state ->
            state.copy(
                lessons = state.lessons.map { if (it.id == record.id) record else it },
                annotations = state.annotations.map { old ->
                    replied?.takeIf { it.id == old.id }?.let { old.copy(answer = it.answer, status = it.status) } ?: old
                },
                receipts = state.receipts + LessonReceipt(requestId, fingerprint, record.id),
            )
        }
        return record
    }

    @Synchronized
    fun saveBrowse(lessonId: String, browse: LessonBrowse, stillCurrent: () -> Boolean = { true }) {
        workspace.update { if (stillCurrent()) it.copy(browse = it.browse + (lessonId to browse)) else it }
    }

    fun targetIsCurrent(record: LessonRecord, target: LessonTarget): Boolean =
        lessonTargetIsCurrent(record, workspace.snapshot.annotations, target) { readExample(record, it) }
}
