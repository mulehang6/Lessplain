package com.master.understanding.core

import com.google.gson.Gson
import com.master.understanding.model.Evidence
import com.master.understanding.model.SourceAnchor
import com.master.understanding.model.UnderstandingAnswer
import com.master.understanding.model.UnderstandingContext
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.model.UNDERSTANDING_SCHEMA_VERSION
import com.master.understanding.model.requireInput
import com.master.understanding.model.validate
import com.master.understanding.storage.UnderstandingRepository
import com.master.understanding.storage.newId

class UnderstandingCore(
    private val repository: UnderstandingRepository,
    private val gson: Gson = Gson(),
) {
    fun captureContext(
        recordId: String? = null,
        nodeId: String? = null,
        anchor: SourceAnchor? = null,
        staticPath: List<String> = emptyList(),
    ): UnderstandingContext {
        anchor?.validate()
        val record = recordId?.let(repository::getRecord)
        if (nodeId != null) {
            requireInput(record != null && record.graph.nodes.any { it.id == nodeId }, "所选节点不属于理解记录")
        }
        val context = UnderstandingContext(
            token = newId(),
            projectId = repository.projectId,
            recordId = recordId,
            recordRevision = record?.revision,
            nodeId = nodeId,
            anchor = anchor,
            staticPath = staticPath.toList(),
            createdAt = System.currentTimeMillis(),
        )
        repository.putContext(context)
        return context
    }

    fun context(token: String): UnderstandingContext = repository.getContext(token)

    /** Persist before copying the handoff payload. */
    fun prepareQuestion(text: String, contextToken: String): PreparedQuestion {
        val normalized = text.trim()
        requireInput(normalized.isNotEmpty(), "问题不能为空")
        val context = repository.getContext(contextToken)
        val question = UnderstandingQuestion(
            id = newId(),
            text = normalized,
            contextToken = contextToken,
            ownerRecordId = context.recordId,
            status = "pending",
            createdAt = System.currentTimeMillis(),
        )
        repository.saveQuestion(question)
        return PreparedQuestion(
            question = question,
            payload = gson.toJson(
                mapOf(
                    "protocolVersion" to UNDERSTANDING_SCHEMA_VERSION,
                    "questionId" to question.id,
                    "contextToken" to contextToken,
                    "question" to question.text,
                ),
            ),
        )
    }

    fun present(
        schemaVersion: Int,
        requestId: String,
        question: String,
        answer: String,
        graph: UnderstandingGraph,
        placement: String,
        contextToken: String? = null,
        questionId: String? = null,
        parentId: String? = null,
    ): UnderstandingRecord {
        validateVersion(schemaVersion)
        requireInput(question.isNotBlank() && answer.isNotBlank(), "问题和回答不能为空")
        graph.validate()
        val savedQuestion = questionId?.let(repository::getQuestion)
        requireInput(savedQuestion == null || savedQuestion.text == question.trim(), "questionId 与原问题不一致")
        val token = contextToken ?: savedQuestion?.contextToken
        requireInput(savedQuestion == null || savedQuestion.contextToken == token, "questionId 与 contextToken 不一致")
        val context = token?.let(repository::getContext)
        val parent = resolveParent(placement, parentId, context)
        val payload = gson.toJson(listOf(schemaVersion, question.trim(), answer.trim(), graph, placement, token, questionId, parentId))
        val json = repository.request(requestId, "present_understanding", payload) { db ->
            val effectiveQuestion = savedQuestion ?: if (placement == "child" && context?.recordId != null) {
                UnderstandingQuestion(
                    id = newId(),
                    text = question.trim(),
                    contextToken = token,
                    ownerRecordId = context.recordId,
                    status = "pending",
                    createdAt = System.currentTimeMillis(),
                ).also { repository.insertQuestion(db, it) }
            } else null
            val record = UnderstandingRecord(
                id = newId(),
                revision = 1,
                parentId = parent,
                questionId = effectiveQuestion?.id,
                question = question.trim(),
                answer = answer.trim(),
                contextToken = token,
                graph = graph,
                createdAt = System.currentTimeMillis(),
            )
            repository.insertRecord(db, record)
            gson.toJson(record)
        }
        return gson.fromJson(json, UnderstandingRecord::class.java)
    }

    fun appendAnswer(
        schemaVersion: Int,
        requestId: String,
        answer: String,
        evidence: List<Evidence> = emptyList(),
        questionId: String? = null,
        contextToken: String? = null,
        question: String? = null,
        replacesAnswerId: String? = null,
    ): UnderstandingAnswer {
        validateVersion(schemaVersion)
        requireInput(answer.isNotBlank(), "回答不能为空")
        evidence.forEach { item ->
            requireInput(item.id.isNotBlank(), "证据 ID 不能为空")
            item.anchor.validate()
            item.dependencies.forEach(SourceAnchor::validate)
        }
        val savedQuestion = questionId?.let(repository::getQuestion)
        requireInput(savedQuestion != null || !contextToken.isNullOrBlank() && !question.isNullOrBlank(), "需要 questionId 或固定来源与问题")
        if (replacesAnswerId != null) {
            requireInput(savedQuestion != null, "替换回答需要 questionId")
            requireInput(repository.getAnswer(replacesAnswerId).questionId == savedQuestion?.id, "旧回答不属于该问题")
        }
        if (savedQuestion != null && contextToken != null) {
            requireInput(savedQuestion.contextToken == contextToken, "questionId 与 contextToken 不一致")
        }
        val token = savedQuestion?.contextToken ?: contextToken!!
        val context = repository.getContext(token)
        val payload = gson.toJson(listOf(schemaVersion, answer.trim(), evidence, questionId, token, question?.trim(), replacesAnswerId))
        val json = repository.request(requestId, "append_understanding_answer", payload) { db ->
            if (replacesAnswerId != null) {
                requireInput(repository.answersForQuestion(savedQuestion!!.id).any { it.id == replacesAnswerId }, "旧回答已被替换")
            }
            val effectiveQuestion = savedQuestion ?: UnderstandingQuestion(
                id = newId(),
                text = question!!.trim(),
                contextToken = token,
                ownerRecordId = context.recordId,
                status = "pending",
                createdAt = System.currentTimeMillis(),
            ).also { repository.insertQuestion(db, it) }
            val result = UnderstandingAnswer(
                id = newId(),
                questionId = effectiveQuestion.id,
                answer = answer.trim(),
                evidence = evidence,
                createdAt = System.currentTimeMillis(),
                supersedesId = replacesAnswerId,
            )
            repository.insertAnswer(db, result)
            gson.toJson(result)
        }
        return gson.fromJson(json, UnderstandingAnswer::class.java)
    }

    private fun validateVersion(value: Int) {
        if (value != UNDERSTANDING_SCHEMA_VERSION) throw UnderstandingInputException("不支持的 schemaVersion：$value")
    }

    private fun resolveParent(placement: String, parentId: String?, context: UnderstandingContext?): String? = when (placement) {
        "root" -> {
            requireInput(parentId == null, "根记录不能指定 parentId")
            null
        }
        "child" -> {
            requireInput(parentId == null, "child 归属由固定来源决定")
            context?.recordId ?: throw UnderstandingInputException("没有来源记录，须明确选择 root 或 explicit")
        }
        "sibling" -> {
            requireInput(parentId == null, "sibling 归属由固定来源决定")
            val source = context?.recordId ?: throw UnderstandingInputException("没有来源记录")
            repository.getRecord(source).parentId
        }
        "explicit" -> {
            val requested = parentId ?: throw UnderstandingInputException("explicit 需要 parentId")
            repository.getRecord(requested)
            requested
        }
        else -> throw UnderstandingInputException("placement 无效")
    }
}

data class PreparedQuestion(val question: UnderstandingQuestion, val payload: String)
