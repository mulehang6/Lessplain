package com.lessplain.mcp

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lessplain.model.UnderstandingContext
import com.lessplain.model.requireInput
import com.lessplain.storage.PendingNoteBatch
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.consumeReadFeedback

/** Agent 可根据用户反馈指定注释；普通源码追问沿用回答流程，不按文字猜测类别。 */
internal fun parseFeedbackQuestionIds(json: String): Set<String> {
    if (json.isBlank()) return emptySet()
    val value = JsonParser.parseString(json)
    requireInput(value.isJsonArray, "feedbackQuestionIds 必须是 JSON 字符串数组")
    return value.asJsonArray.mapTo(linkedSetOf()) {
        requireInput(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank(),
            "feedbackQuestionIds 只能包含非空 questionId")
        it.asString
    }
}

/** 先完成响应序列化，再原子移除本次读取的反馈；读取失败不会提前清理。 */
internal fun readUnderstandingContextResponse(
    repository: UnderstandingRepository,
    context: UnderstandingContext,
    feedbackQuestionIds: Set<String> = emptySet(),
    serialize: (Map<String, Any?>) -> String = { Gson().toJson(it) },
): String {
    val payload = understandingContextPayload(repository, context)
    val batch = payload["pendingBatch"] as PendingNoteBatch?
    val items = batch?.items.orEmpty().filter { it.questionId in feedbackQuestionIds }
    requireInput(items.map { it.questionId }.toSet() == feedbackQuestionIds,
        "指定的反馈不在本次读取范围内，请读取对应理解图的待答注释")
    val response = serialize(if (feedbackQuestionIds.isEmpty()) payload else
        payload + ("consumedFeedbackIds" to feedbackQuestionIds.toList()))
    if (items.isNotEmpty()) repository.consumeReadFeedback(items)
    return response
}
