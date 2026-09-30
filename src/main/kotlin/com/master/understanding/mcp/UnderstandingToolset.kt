package com.master.understanding.mcp

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.storage.pendingQuestions
import kotlinx.coroutines.currentCoroutineContext

class UnderstandingToolset : McpToolset {
    private val gson = Gson()

    @Suppress("unused")
    @McpTool(name = "get_understanding_context")
    @McpDescription("读取固定的提问来源。省略 token 时优先读取用户选中的待答节点注释；后续传回 contextToken 可读取同一来源。")
    suspend fun getUnderstandingContext(
        @McpDescription("可选。先前返回的固定来源 token；空字符串表示捕获当前来源。") contextToken: String = "",
    ): String = runTool {
        val service = service()
        val pending = if (contextToken.isBlank()) service.selectedPendingQuestion() else null
        if (contextToken.isBlank() && pending == null && service.repository.pendingQuestions().size > 1) {
            mcpFail("存在多条待答注释，请先在节点的注释气泡中选中目标")
        }
        val context = when {
            contextToken.isNotBlank() -> service.core.context(contextToken)
            pending != null -> service.core.context(pending.contextToken)
            else -> service.captureCurrentContext()
        }
        val record = context.recordId?.let(service.repository::getRecord)?.copy(answer = "")
        val questions = (service.repository.questionsForContext(context.token) +
            (record?.let { service.repository.questionsForRecord(it.id) } ?: emptyList()))
            .distinctBy { it.id }
        gson.toJson(mapOf("context" to context, "record" to record, "questions" to questions,
            "pendingQuestionId" to pending?.id))
    }

    @Suppress("unused")
    @McpTool(name = "present_understanding")
    @McpDescription("保存或修订代码理解图。必须有实际图节点，不接受纯文字回答；节点和关系标签保持简短，不提交坐标。")
    suspend fun presentUnderstanding(
        @McpDescription("协议版本，当前为 1。") schemaVersion: Int,
        @McpDescription("此次写入的唯一请求 ID；相同内容重试返回原结果。") requestId: String,
        @McpDescription("问题原文。") question: String,
        @McpDescription("JSON 对象，含 nodes、edges、evidence 数组。关系类型为 control_flow、call、data_flow、dependency 或 structure。") graph: String,
        @McpDescription("归属：root、child、sibling、explicit 或 revise。revise 更新原记录的图。") placement: String,
        @McpDescription("可选的简短显示标题；原始问题另行保留。") displayTitle: String = "",
        @McpDescription("固定来源 token；问题已复制时可由 questionId 取得。") contextToken: String = "",
        @McpDescription("可选，复制问题时返回的 questionId。") questionId: String = "",
        @McpDescription("placement=explicit 时的父记录 ID。") parentId: String = "",
        @McpDescription("placement=revise 时必填，需修订的原记录 ID。") reviseRecordId: String = "",
        @McpDescription("placement=revise 时必填，原记录的当前版本。") expectedRevision: Int = 0,
    ): String = runTool {
        val service = service()
        val parsed = parseGraphPayload(graph)
        val record = if (placement == "revise") {
            if (contextToken.isNotBlank() || questionId.isNotBlank() || parentId.isNotBlank() || displayTitle.isNotBlank()) {
                mcpFail("修订会保留原记录的来源、归属和标题")
            }
            service.core.revise(schemaVersion, requestId, question, parsed, reviseRecordId, expectedRevision)
        } else {
            if (reviseRecordId.isNotBlank() || expectedRevision != 0) mcpFail("只有 revise 归属可指定修订参数")
            service.core.present(
                schemaVersion, requestId, question, parsed, placement,
                contextToken.takeIf(String::isNotBlank), questionId.takeIf(String::isNotBlank),
                parentId.takeIf(String::isNotBlank), displayTitle.takeIf(String::isNotBlank),
            )
        }
        service.resultSaved(recordId = record.id, questionId = record.questionId, revision = record.revision)
        gson.toJson(mapOf("recordId" to record.id, "revision" to record.revision,
            "parentId" to record.parentId, "questionId" to record.questionId))
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
