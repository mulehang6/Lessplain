package com.lessplain.ide

import com.lessplain.model.SourceAnchor
import com.lessplain.storage.UnderstandingRepository

/** 一页对应产生某个回答的问题；代码始终来自该问题自己的冻结上下文。 */
internal data class QuestionOrigin(
    val questionId: String,
    val question: String,
    val anchor: SourceAnchor?,
    val answerRecordId: String,
)

/** 只能在后台读取。根图展示起始问题，追问层只读取本记录、本节点的已回答问题。 */
internal fun readQuestionOrigins(repository: UnderstandingRepository, layer: GraphLayerKey): List<QuestionOrigin> {
    val record = repository.getRecord(layer.recordId)
    if (layer.questionNodeId == null) return listOf(QuestionOrigin(
        record.questionId ?: "root:${record.id}", record.question,
        record.contextToken?.let(repository::getContext)?.anchor, record.id,
    ))
    return repository.questionsForRecord(record.id).mapNotNull { question ->
        val answer = repository.recordForQuestion(question.id) ?: return@mapNotNull null
        val context = repository.getContext(question.contextToken)
        if (context.recordId != record.id || context.nodeId != layer.questionNodeId) return@mapNotNull null
        QuestionOrigin(question.id, question.text, context.anchor, answer.id)
    }
}
