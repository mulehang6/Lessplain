package com.lessplain.ide

import com.lessplain.model.SourceAnchor
import com.lessplain.storage.UnderstandingRepository

/** 目标使用真实回答记录和问题身份，与当前标签、层级和节点选择无关。 */
internal data class QuestionOriginTarget(val recordId: String, val questionId: String? = null)
internal data class QuestionOrigin(val questionId: String, val question: String, val anchor: SourceAnchor?, val answerRecordId: String)

internal fun readQuestionOrigin(repository: UnderstandingRepository, target: QuestionOriginTarget): QuestionOrigin {
    val record = repository.getRecord(target.recordId)
    val questionId = target.questionId ?: record.questionId
    if (questionId != null) {
        val question = repository.getQuestion(questionId)
        require(repository.recordForQuestion(questionId)?.id == record.id) { "问题与回答记录不一致" }
        val context = repository.getContext(question.contextToken)
        require(context.recordId == question.ownerRecordId) { "问题与冻结来源的归属不一致" }
        return QuestionOrigin(question.id, question.text, context.anchor, record.id)
    }
    require(record.parentId == null) { "该回答缺少原始问题" }
    return QuestionOrigin("root:${record.id}", record.question, record.contextToken?.let(repository::getContext)?.anchor, record.id)
}
