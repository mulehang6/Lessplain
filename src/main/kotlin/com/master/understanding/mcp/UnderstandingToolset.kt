package com.master.understanding.mcp

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.master.understanding.ide.UnderstandingService
import kotlinx.coroutines.currentCoroutineContext

class UnderstandingToolset : McpToolset {
    private val gson = Gson()

    @Suppress("unused")
    @McpTool(name = "get_understanding_context")
    @McpDescription("读取固定的代码理解提问来源。首次调用时捕获当前图节点或编辑器选择；后续传回 contextToken 可读取同一来源，不随界面切换而改变。")
    suspend fun getUnderstandingContext(
        @McpDescription("可选。先前返回的固定来源 token；空字符串表示捕获当前来源。") contextToken: String = "",
    ): String = runTool {
        val service = service()
        val context = if (contextToken.isBlank()) service.captureCurrentContext() else service.core.context(contextToken)
        val record = context.recordId?.let(service.repository::getRecord)
        val questions = (service.repository.questionsForContext(context.token) +
            (record?.let { service.repository.questionsForRecord(it.id) } ?: emptyList()))
            .distinctBy { it.id }
        val answers = questions.associate { it.id to service.repository.answersForQuestion(it.id) }
        gson.toJson(mapOf("context" to context, "record" to record, "questions" to questions, "answers" to answers))
    }

    @Suppress("unused")
    @McpTool(name = "present_understanding")
    @McpDescription("创建并保存一份独立代码理解记录。图由稳定节点、关系、证据组成，不提供坐标。不会覆盖旧图。")
    suspend fun presentUnderstanding(
        @McpDescription("协议版本，当前为 1。") schemaVersion: Int,
        @McpDescription("此次写入的唯一请求 ID；相同内容重试返回原结果。") requestId: String,
        @McpDescription("问题原文。") question: String,
        @McpDescription("简短回答。") answer: String,
        @McpDescription("JSON 对象，含 nodes、edges、evidence 数组。关系类型为 control_flow、call、data_flow、dependency 或 structure。") graph: String,
        @McpDescription("归属：root、child、sibling 或 explicit。无来源时须显式选择 root 或 explicit。") placement: String,
        @McpDescription("固定来源 token；问题已复制时可由 questionId 取得。") contextToken: String = "",
        @McpDescription("可选，复制问题时返回的 questionId。") questionId: String = "",
        @McpDescription("placement=explicit 时的父记录 ID。") parentId: String = "",
    ): String = runTool {
        val service = service()
        val parsed = parseGraphPayload(graph)
        val record = service.core.present(
            schemaVersion, requestId, question, answer, parsed, placement,
            contextToken.takeIf(String::isNotBlank), questionId.takeIf(String::isNotBlank), parentId.takeIf(String::isNotBlank),
        )
        service.notifyChanged()
        gson.toJson(mapOf("recordId" to record.id, "revision" to record.revision,
            "parentId" to record.parentId, "questionId" to record.questionId))
    }

    @Suppress("unused")
    @McpTool(name = "append_understanding_answer")
    @McpDescription("保存补充回答及其固定来源，不强制创建新图。questionId 会把回答挂回复制前的原问题。")
    suspend fun appendUnderstandingAnswer(
        @McpDescription("协议版本，当前为 1。") schemaVersion: Int,
        @McpDescription("此次写入的唯一请求 ID。") requestId: String,
        @McpDescription("回答正文。") answer: String,
        @McpDescription("复制问题时返回的 questionId。") questionId: String = "",
        @McpDescription("没有 questionId 时需要固定来源 token。") contextToken: String = "",
        @McpDescription("没有 questionId 时需要问题原文。") question: String = "",
        @McpDescription("可选 JSON 证据数组。") evidence: String = "[]",
        @McpDescription("可选。要替换的旧回答 ID；新回答将成为此问题显示的回答，旧回答保留在存储中。") replacesAnswerId: String = "",
    ): String = runTool {
        val service = service()
        val parsed = parseEvidencePayload(evidence)
        val result = service.core.appendAnswer(
            schemaVersion, requestId, answer, parsed,
            questionId.takeIf(String::isNotBlank), contextToken.takeIf(String::isNotBlank), question.takeIf(String::isNotBlank),
            replacesAnswerId.takeIf(String::isNotBlank),
        )
        service.notifyChanged()
        gson.toJson(mapOf("answerId" to result.id, "questionId" to result.questionId))
    }

    private suspend fun service(): UnderstandingService {
        val project = currentCoroutineContext().projectOrNull ?: mcpFail("没有活动项目")
        return project.getService(UnderstandingService::class.java)
    }

    private suspend fun <T> runTool(block: suspend () -> T): T = try {
        block()
    } catch (failure: IllegalArgumentException) {
        mcpFail(failure.message ?: "输入无效")
    } catch (failure: IllegalStateException) {
        mcpFail(failure.message ?: "当前状态不允许操作")
    } catch (failure: Exception) {
        mcpFail("保存或读取失败：${failure.message}")
    }
}
