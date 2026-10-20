package com.lessplain.mcp

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.lessplain.ide.UnderstandingService
import com.lessplain.lesson.LessonOrigin
import com.lessplain.lesson.LessonService
import com.lessplain.model.requireInput
import com.lessplain.model.understandingSchemaRevision
import com.lessplain.model.resolvedAnchors
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException

class UnderstandingToolset : McpToolset {
    private val gson = Gson()

    @Suppress("unused")
    @McpTool(name = "get_understanding_context")
    @McpDescription("读取固定来源、理解图的待答注释或教材。lesson: 开头的 contextToken 返回冻结的教材问题、选区和 lessonProtocol；lessonId 可回看已有教材。图上的源码追问通过子图回答；缺陷反馈可传 feedbackQuestionIds，在读取成功后直接清理。")
    suspend fun getUnderstandingContext(
        @McpDescription("可选。先前返回的固定来源 token；空字符串表示捕获当前来源。") contextToken: String = "",
        @McpDescription("可选。复查批量队列时传回 pendingBatch.scopeRecordId，避免界面切换改变范围；不能与 contextToken 同时指定。") pendingScopeRecordId: String = "",
        @McpDescription("可选。用户缺陷反馈的 questionId JSON 字符串数组，例如 [\"id1\",\"id2\"]；仍返回这些注释全文，并在成功读取后直接删除，不回答源码追问。") feedbackQuestionIds: String = "",
        @McpDescription("可选。读取指定教材及其批注；不能与图队列或反馈消费混用。教材问题优先传 lesson: 开头的 contextToken。") lessonId: String = "",
    ): String = runTool {
        if (contextToken.startsWith("lesson:") || lessonId.isNotBlank()) {
            requireInput(pendingScopeRecordId.isBlank() && feedbackQuestionIds.isBlank(), "教材读取不能混用图队列或反馈消费")
            return@runTool gson.toJson(lessonService().contextPayload(contextToken, lessonId))
        }
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
    @McpDescription("保存理解图、按需教材或教材原位补充。presentation=graph 时必须有实际图节点，校验全部源码锚点；version 可由当前 IDE 文档补齐，lineHint 回填实际行号。边 summary 为短关系说明，coverage 为完整依据，inferred=true 时必须提供依据。presentation=lesson/annotationReply 使用冻结 contextToken 和 lesson JSON，遵循 get_understanding_context 返回的 lessonProtocol。所有图均不提交坐标。")
    suspend fun presentUnderstanding(
        @McpDescription("协议版本字符串：1.4。兼容字符串 4 及不带 clusters 的字符串 3；按 get_understanding_context 返回的版本提交。") schemaVersion: String,
        @McpDescription("此次写入的唯一请求 ID；相同内容重试返回原结果。") requestId: String,
        @McpDescription("问题原文。") question: String,
        @McpDescription("图 JSON，含 nodes、edges、evidence；根图可选完整互斥的单层 clusters。节点 flowRoles 可为 start/end，结束节点可用 endOutcome=success/failure；不提交 decision。边关系 control_flow/call/data_flow/dependency/structure；分叉控制流写 condition，可选 summary/coverage/inferred。不能提交坐标。教材结果省略此项。") graph: String = "",
        @McpDescription("图归属 root/child/sibling/explicit/revise。教材结果使用 root，具体归属由冻结来源确定。") placement: String = "root",
        @McpDescription("可选的简短显示标题；原始问题另行保留。") displayTitle: String = "",
        @McpDescription("固定来源 token；问题已复制时可由 questionId 取得。") contextToken: String = "",
        @McpDescription("可选，复制问题时返回的 questionId。") questionId: String = "",
        @McpDescription("placement=explicit 时的父记录 ID。") parentId: String = "",
        @McpDescription("placement=revise 时必填，需修订的原记录 ID。") reviseRecordId: String = "",
        @McpDescription("placement=revise 时必填，原记录的当前版本。") expectedRevision: Int = 0,
        @McpDescription("结果类型：graph（兼容旧调用）、lesson（教材）、annotationReply（教材原位补充）。教材不能包装成图节点。") presentation: String = "graph",
        @McpDescription("教材 JSON 字符串：{title,blocks:[{id,kind,text,level?,language?,exampleId?,columns?,rows?,graph?}],examples:[{id,fileName,code,dependencies:[]}]}。kind=text/code/table/diagram，示例文件须为独立 .main.kts；用 exampleId 引用同一份示例，不重复 text。详情见 get_understanding_context 的 lessonProtocol。") lesson: String = "",
    ): String = runTool {
        if (presentation != "graph") {
            requireInput(presentation in setOf("lesson", "annotationReply"), "不支持的结果类型")
            requireInput(contextToken.isNotBlank(), "教材结果必须提供固定来源 token")
            requireInput(placement == "root" && graph.isBlank() && parentId.isBlank() &&
                reviseRecordId.isBlank() && expectedRevision == 0 && questionId.isBlank(), "教材归属使用冻结来源，不能混用图参数")
            understandingSchemaRevision(schemaVersion)
            val parsed = parseLessonPayload(lesson)
            val origin = if (!contextToken.startsWith("lesson:")) {
                requireInput(presentation == "lesson", "原位补充需要教材问题的 token")
                val source = service().core.context(contextToken)
                LessonOrigin(requireNotNull(source.anchor) { "教材需要源码选区来源" }, source.recordId, source.nodeId)
            } else null
            val record = lessonService().present(requestId, contextToken, parsed.title, parsed.blocks, parsed.examples,
                presentation == "annotationReply", question, origin)
            return@runTool gson.toJson(mapOf("lessonId" to record.id, "revision" to record.revision, "presentation" to presentation))
        }
        requireInput(lesson.isBlank(), "图结果不能混用教材内容")
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

    private suspend fun lessonService(): LessonService {
        val project = currentCoroutineContext().projectOrNull ?: mcpFail("没有活动项目")
        return project.getService(LessonService::class.java)
    }

    private suspend fun <T> runTool(block: suspend () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        mcpFail(failure.message ?: "输入无效")
    } catch (failure: IllegalStateException) {
        mcpFail(failure.message ?: "当前状态不允许操作")
    } catch (failure: Exception) {
        mcpFail("保存或读取失败：${failure.message}")
    }
}
