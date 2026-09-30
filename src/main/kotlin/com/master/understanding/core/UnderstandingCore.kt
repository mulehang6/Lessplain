package com.master.understanding.core

import com.google.gson.Gson
import com.master.understanding.model.SourceAnchor
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
import com.master.understanding.storage.reviseRecordGraph

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
        graph: UnderstandingGraph,
        placement: String,
        contextToken: String? = null,
        questionId: String? = null,
        parentId: String? = null,
        displayTitle: String? = null,
    ): UnderstandingRecord {
        validateVersion(schemaVersion)
        requireInput(question.isNotBlank(), "问题不能为空")
        requireInput(displayTitle == null || displayTitle.isNotBlank(), "标题不能为空")
        graph.validate()
        requireInput(graph.nodes.any { it.kind != "placeholder" }, "回答必须包含实际图节点")
        val savedQuestion = questionId?.let(repository::getQuestion)
        requireInput(savedQuestion == null || savedQuestion.text == question.trim(), "questionId 与原问题不一致")
        requireInput(savedQuestion == null || placement == "child", "节点注释的回答必须作为子图保存")
        val token = contextToken ?: savedQuestion?.contextToken
        requireInput(savedQuestion == null || savedQuestion.contextToken == token, "questionId 与 contextToken 不一致")
        val context = token?.let(repository::getContext)
        val parent = resolveParent(placement, parentId, context)
        val payload = gson.toJson(listOf(schemaVersion, question.trim(), graph, placement, token, questionId, parentId, displayTitle?.trim()))
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
                answer = "",
                contextToken = token,
                graph = graph,
                createdAt = System.currentTimeMillis(),
                displayTitle = displayTitle?.trim(),
            )
            repository.insertRecord(db, record)
            gson.toJson(record)
        }
        return gson.fromJson(json, UnderstandingRecord::class.java)
    }

    fun revise(
        schemaVersion: Int,
        requestId: String,
        question: String,
        graph: UnderstandingGraph,
        recordId: String,
        expectedRevision: Int,
    ): UnderstandingRecord {
        validateVersion(schemaVersion)
        requireInput(question.isNotBlank(), "问题不能为空")
        requireInput(recordId.isNotBlank() && expectedRevision > 0, "修订需要记录 ID 和预期版本")
        graph.validate()
        requireInput(graph.nodes.any { it.kind != "placeholder" }, "回答必须包含实际图节点")
        val payload = gson.toJson(listOf(schemaVersion, question.trim(), graph, "revise", recordId, expectedRevision))
        val json = repository.request(requestId, "present_understanding", payload) { db ->
            gson.toJson(repository.reviseRecordGraph(db, recordId, expectedRevision, question.trim(), graph))
        }
        return gson.fromJson(json, UnderstandingRecord::class.java)
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
