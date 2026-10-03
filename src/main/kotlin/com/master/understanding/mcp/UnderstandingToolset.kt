package com.master.understanding.mcp

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.requireInput
import kotlinx.coroutines.currentCoroutineContext

class UnderstandingToolset : McpToolset {
    private val gson = Gson()

    @Suppress("unused")
    @McpTool(name = "get_understanding_context")
    @McpDescription("读取固定来源，以及当前理解图及其追问中的全部待答注释。pendingBatch 每项含 questionId、原问题和固定 context，逐条回答后自动移除注释。")
    suspend fun getUnderstandingContext(
        @McpDescription("可选。先前返回的固定来源 token；空字符串表示捕获当前来源。") contextToken: String = "",
        @McpDescription("可选。复查批量队列时传回 pendingBatch.scopeRecordId，避免界面切换改变范围；不能与 contextToken 同时指定。") pendingScopeRecordId: String = "",
    ): String = runTool {
        val service = service()
        requireInput(contextToken.isBlank() || pendingScopeRecordId.isBlank(), "固定来源与批量范围不能同时指定")
        val context = when {
            contextToken.isNotBlank() -> service.core.context(contextToken)
            pendingScopeRecordId.isNotBlank() -> service.core.captureContext(pendingScopeRecordId)
            else -> service.captureCurrentContext()
        }
        gson.toJson(understandingContextPayload(service.repository, context))
    }

    @Suppress("unused")
    @McpTool(name = "present_understanding")
    @McpDescription("保存或修订代码理解图。必须有实际图节点，不接受纯文字回答；节点和关系标签保持简短，不提交坐标。")
    suspend fun presentUnderstanding(
        @McpDescription("协议版本：4；兼容不带 clusters 的版本 3。") schemaVersion: Int,
        @McpDescription("此次写入的唯一请求 ID；相同内容重试返回原结果。") requestId: String,
        @McpDescription("问题原文。") question: String,
        @McpDescription("JSON 对象，含 nodes、edges、evidence。版本 4 的根图可选单层 clusters=[{id,title,nodeIds}]，完整且互斥地覆盖节点，不提交坐标；子追问一问一组。修订时 clusters 省略/null 保留、[] 清空。节点 flowRoles 可含 start/end；endOutcome 可选 success/failure 且需要 end。业务角色按源码标注；分支图标由可浏览后继自动计算。关系类型 control_flow、call、data_flow、dependency、structure；控制流分支需 condition。") graph: String,
        @McpDescription("归属：root、child、sibling、explicit 或 revise。revise 更新原记录的图。") placement: String,
        @McpDescription("可选的简短显示标题；原始问题另行保留。") displayTitle: String = "",
        @McpDescription("固定来源 token；问题已复制时可由 questionId 取得。") contextToken: String = "",
        @McpDescription("可选，复制问题时返回的 questionId。") questionId: String = "",
        @McpDescription("placement=explicit 时的父记录 ID。") parentId: String = "",
        @McpDescription("placement=revise 时必填，需修订的原记录 ID。") reviseRecordId: String = "",
        @McpDescription("placement=revise 时必填，原记录的当前版本。") expectedRevision: Int = 0,
    ): String = runTool {
        val service = service()
        val parsed = parseGraphSubmission(graph, schemaVersion)
        val record = if (placement == "revise") {
            if (contextToken.isNotBlank() || questionId.isNotBlank() || parentId.isNotBlank() || displayTitle.isNotBlank()) {
                mcpFail("修订会保留原记录的来源、归属和标题")
            }
            service.core.revise(schemaVersion, requestId, question, parsed.graph, reviseRecordId, expectedRevision, parsed.clusterUpdate)
        } else {
            if (reviseRecordId.isNotBlank() || expectedRevision != 0) mcpFail("只有 revise 归属可指定修订参数")
            service.core.present(
                schemaVersion, requestId, question, parsed.graph, placement,
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
