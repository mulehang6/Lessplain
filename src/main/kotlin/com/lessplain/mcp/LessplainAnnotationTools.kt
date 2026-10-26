package com.lessplain.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.lessplain.storage.questionsPage
import com.lessplain.storage.answerMaterialQuestion
import com.lessplain.storage.readQuestion
import com.lessplain.storage.sha256
import com.lessplain.storage.PendingNoteItem
import com.lessplain.storage.consumeReadFeedback
import com.lessplain.tutorial.*
import com.intellij.mcpserver.McpToolCallResult

class LessplainAnnotationTools : LessplainToolsSupport() {
    @McpTool(name = "lessplain_annotations_list")
    @McpDescription("查询当前项目或指定产物的笔记/追问/反馈。status=pending 仅返回已提交待答问题；返回冻结来源，读取不删除记录。")
    suspend fun annotations(materialId: String? = null, kind: String = "question", status: String = "", limit: Int = 20, cursor: String = ""): McpToolCallResult = durableResult {
        val tutorials = tutorial(); val graphs = graph()
        tutorialRequire(kind in setOf("note", "question", "feedback"), "INVALID_KIND", "kind", "kind 为 note/question/feedback")
        val start = pageStart(limit, cursor)
        val annotations = tutorials.store.annotations(materialId).filter { !it.isEmptyDraft && it.kind == kind && (status.isBlank() || it.status == status) }
        val items = annotations.drop(start).take(limit).map { annotation ->
            mapOf("id" to annotation.id, "questionId" to annotation.id.takeIf { annotation.kind == "question" },
                "materialId" to annotation.materialId, "kind" to annotation.kind, "status" to annotation.status,
                "text" to annotation.text, "quote" to annotation.quote, "orphaned" to annotation.orphaned,
                "answer" to annotation.answer, "answerMaterialId" to annotation.answerMaterialId,
                "context" to annotation.contextId?.let(tutorials.store::context))
        }.toMutableList<Map<String, Any?>>()
        var total = annotations.size
        if (kind != "note" && (materialId == null || !materialId.startsWith("tut_"))) {
            val questions = graphs.repository.questionsPage(materialId, status.ifBlank { "" }, kind == "feedback",
                (start - annotations.size).coerceAtLeast(0), limit - items.size)
            total += questions.total
            questions.items.forEach { question ->
                val frozen = graphs.core.context(question.contextToken)
                val context = importContext(tutorials, frozen, question.text)
                items += mapOf("id" to question.id, "questionId" to question.id, "kind" to "question", "feedbackEligible" to (frozen.nodeId != null), "status" to question.status, "text" to question.text, "materialId" to question.ownerRecordId, "context" to context,
                    "answers" to graphs.repository.answersForQuestion(question.id))
            }
        }
        val sourceIds = items.mapNotNull { it["context"] as? TutorialContext }.flatMap { it.sourceIds }.distinct()
        page(items, total, start) + ("sources" to sourceIds.map(tutorials.store::source))
    }

    @McpTool(name = "lessplain_question_answer")
    @McpDescription("按问题 ID 回答已提交问题，可附已创建产物 ID。回答属于冻结来源，不依赖活动标签，不覆盖笔记或正文。")
    suspend fun answer(questionId: String, markdown: String, materialId: String? = null): McpToolCallResult = writeResult("question_answer", listOf(questionId, markdown, materialId), useTutorialReceipt = questionId.startsWith("q_")) {
        tutorialRequire(markdown.isNotBlank(), "EMPTY_ANSWER", "markdown", "回答不能为空")
        val tutorials = tutorial(); val graphs = graph()
        if (materialId != null) { if (materialId.startsWith("tut_")) tutorials.store.record(materialId) else graphs.repository.getRecord(materialId) }
        if (questionId.startsWith("q_")) tutorials.answer(questionId, markdown, materialId)
        else graphs.repository.answerMaterialQuestion(questionId, markdown, materialId).also { graphs.notifyChanged() }
    }

    @McpTool(name = "lessplain_feedback_consume")
    @McpDescription("显式消费已读取的缺陷反馈：返回全文，再清理指定反馈。源码追问不能用此工具消费，普通读取无此副作用。")
    suspend fun feedback(ids: List<String>): McpToolCallResult = result {
        val service = graph()
        val normalized = ids.distinct().sorted()
        val payload = gson.toJson(normalized)
        // 回执与反馈在同一数据库事务内持久化，不依赖教程文件的异步写入。
        val response = service.repository.request("feedback:${sha256(payload)}", "feedback_consume", payload) { db ->
            val questions = normalized.map { service.repository.readQuestion(db, it) }
            tutorialRequire(questions.all { it.status == "pending" && service.core.context(it.contextToken).nodeId != null }, "NOT_FEEDBACK", "ids", "只允许显式指定图节点上的待处理反馈；源码独立问题不能消费")
            val items = questions.map { question -> PendingNoteItem(question.id, question.text, service.core.context(question.contextToken)) }
            val serialized = gson.toJson(mapOf("feedback" to items, "consumedIds" to normalized))
            service.repository.consumeReadFeedback(db, items)
            serialized
        }
        service.notifyChanged()
        com.google.gson.JsonParser.parseString(response)
    }
}
