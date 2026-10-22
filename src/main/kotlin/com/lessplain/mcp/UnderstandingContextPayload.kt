package com.lessplain.mcp

import com.lessplain.model.UnderstandingContext
import com.lessplain.model.UNDERSTANDING_SCHEMA_VERSION
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.pendingNoteBatch

/** 队列范围及各项的固定来源，与当前选择节点分开处理。 */
internal fun understandingContextPayload(repository: UnderstandingRepository, context: UnderstandingContext): Map<String, Any?> {
    val record = context.recordId?.let(repository::getRecord)?.copy(answer = "")
    val batch = record?.let { repository.pendingNoteBatch(it.id) }
    val questions = (repository.questionsForContext(context.token) +
        (record?.let { repository.questionsForRecord(it.id) } ?: emptyList()))
        .distinctBy { it.id }.filter { it.status != "cancelled" }
    return mapOf("schemaVersion" to UNDERSTANDING_SCHEMA_VERSION, "context" to context, "record" to record, "questions" to questions,
        "pendingQuestionId" to questions.singleOrNull { it.status == "pending" && it.contextToken == context.token }?.id,
        "pendingBatch" to batch, "capabilities" to mapOf("nodeSemantics" to true, "pendingNoteBatch" to true,
            "rootClusters" to true, "completeRouteSelection" to true,
            "anchorValidation" to true, "inferredEdges" to true, "edgeSummaries" to true, "feedbackConsumption" to true))
}
