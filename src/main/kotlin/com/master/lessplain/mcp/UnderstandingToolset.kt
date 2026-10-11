package com.master.lessplain.mcp

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.master.lessplain.ide.UnderstandingService
import com.master.lessplain.model.requireInput
import com.master.lessplain.model.understandingSchemaRevision
import com.master.lessplain.model.resolvedAnchors
import kotlinx.coroutines.currentCoroutineContext

class UnderstandingToolset : McpToolset {
    private val gson = Gson()

    @Suppress("unused")
    @McpTool(name = "get_understanding_context")
    @McpDescription("读取固定来源及当前理解图的全部待答注释。源码追问通过子图回答后移除；缺陷反馈可传 feedbackQuestionIds，在本次读取成功后直接清理，不要求子图或修复验收，也不保留处理记录。")
    suspend fun getUnderstandingContext(
        @McpDescription("可选。先前返回的固定来源 token；空字符串表示捕获当前来源。") contextToken: String = "",
        @McpDescription("可选。复查批量队列时传回 pendingBatch.scopeRecordId，避免界面切换改变范围；不能与 contextToken 同时指定。") pendingScopeRecordId: String = "",
        @McpDescription("可选。用户缺陷反馈的 questionId JSON 字符串数组，例如 [\"id1\",\"id2\"]；仍返回这些注释全文，并在成功读取后直接删除，不回答源码追问。") feedbackQuestionIds: String = "",
    ): String = runTool {
        val service = service()
        requireInput(contextToken.isBlank() || pendingScopeRecordId.isBlank(), "固定来源与批量范围不能同时指定")
        val context = when {
            contextToken.isNotBlank() -> service.core.context(contextToken)
            pendingScopeRecordId.isNotBlank() -> service.core.captureContext(pendingScopeRecordId)
            else -> service.captureCurrentContext()
        }
        val ids = parseFeedbackQuestionIds(feedbackQuestionIds)
        val response = readUnderstandingContextResponse(service.repository, context, ids, gson::toJson)
        if (ids.isNotEmpty()) service.notifyChanged()
        response
    }

    @Suppress("unused")
    @McpTool(name = "present_understanding")
    @McpDescription("保存或修订代码理解图。保存前校验全部源码锚点，version 可省略并由当前 IDE 文档补齐，lineHint 回填实际行号；失败一次列出锚点错误。边 inferred=true 表示推断，必须用 coverage 说明依据。必须有实际图节点，不接受纯文字回答，不提交坐标。")
    suspend fun presentUnderstanding(
        @McpDescription("协议版本字符串：1.4。兼容字符串 4 及不带 clusters 的字符串 3；按 get_understanding_context 返回的版本提交。") schemaVersion: String,
        @McpDescription("此次写入的唯一请求 ID；相同内容重试返回原结果。") requestId: String,
        @McpDescription("问题原文。") question: String,
        @McpDescription("JSON 对象，含 nodes、edges、evidence。版本 1.4 的根图可选单层 clusters=[{id,title,nodeIds}]，完整且互斥地覆盖节点，不提交坐标；子追问一问一组。修订时 clusters 省略/null 保留、[] 清空。节点 flowRoles 可含 start/end；endOutcome 可选 success/failure 且需要 end。业务角色按源码标注；五种关系均可前后阅读；分支图标仅按控制流、调用、数据流的不同后继计算。关系类型 control_flow、call、data_flow、dependency、structure；控制流分支需 condition。") graph: String,
        @McpDescription("归属：root、child、sibling、explicit 或 revise。revise 更新原记录的图。") placement: String,
        @McpDescription("可选的简短显示标题；原始问题另行保留。") displayTitle: String = "",
        @McpDescription("固定来源 token；问题已复制时可由 questionId 取得。") contextToken: String = "",
        @McpDescription("可选，复制问题时返回的 questionId。") questionId: String = "",
        @McpDescription("placement=explicit 时的父记录 ID。") parentId: String = "",
        @McpDescription("placement=revise 时必填，需修订的原记录 ID。") reviseRecordId: String = "",
        @McpDescription("placement=revise 时必填，原记录的当前版本。") expectedRevision: Int = 0,
    ): String = runTool {
        val service = service()
        val schemaRevision = understandingSchemaRevision(schemaVersion)
        val parsed = parseGraphSubmission(graph, schemaRevision)
        val record = if (placement == "revise") {
            if (contextToken.isNotBlank() || questionId.isNotBlank() || parentId.isNotBlank() || displayTitle.isNotBlank()) {
                mcpFail("修订会保留原记录的来源、归属和标题")
            }
            service.core.revise(schemaRevision, requestId, question, parsed.graph, reviseRecordId, expectedRevision, parsed.clusterUpdate)
        } else {
            if (reviseRecordId.isNotBlank() || expectedRevision != 0) mcpFail("只有 revise 归属可指定修订参数")
            service.core.present(
                schemaRevision, requestId, question, parsed.graph, placement,
                contextToken.takeIf(String::isNotBlank), questionId.takeIf(String::isNotBlank),
                parentId.takeIf(String::isNotBlank), displayTitle.takeIf(String::isNotBlank),
            )
        }
        service.resultSaved(recordId = record.id, questionId = record.questionId, revision = record.revision)
        gson.toJson(mapOf("recordId" to record.id, "revision" to record.revision,
            "parentId" to record.parentId, "questionId" to record.questionId,
            "resolvedAnchors" to record.graph.resolvedAnchors()))
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
