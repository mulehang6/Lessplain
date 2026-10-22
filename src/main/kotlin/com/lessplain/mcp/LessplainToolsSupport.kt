package com.lessplain.mcp

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.projectOrNull
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingContext
import com.lessplain.model.UnderstandingRecord
import com.lessplain.tutorial.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 统一结构化成功与错误信封。输入保持静态 DTO，输出不再嵌套 JSON 字符串。 */
abstract class LessplainToolsSupport : McpToolset {
    protected val gson = Gson()
    protected suspend fun project() = requireNotNull(currentCoroutineContext().projectOrNull) { "没有活动项目" }
    protected suspend fun tutorial() = project().getService(TutorialService::class.java)
    protected suspend fun graph() = project().getService(UnderstandingService::class.java)
    protected suspend fun writeResult(operation: String, arguments: Any, action: suspend () -> Any?): McpToolCallResult {
        val store = tutorial().store
        val key = com.lessplain.storage.sha256(gson.toJson(listOf(operation, arguments)))
        store.toolReceipt(key)?.let { return response(Json.parseToJsonElement(it) as JsonObject) }
        val response = resultObject { action().also { store.flush() } }
        if (response["ok"].toString() == "true") store.saveToolReceipt(key, response.toString())
        return response(response)
    }
    protected suspend fun result(action: suspend () -> Any?): McpToolCallResult = response(resultObject(action))
    private fun response(value: JsonObject) = McpToolCallResult(arrayOf(McpToolCallResultContent.Text(value.toString())), value, value["ok"].toString() != "true")
    private suspend fun resultObject(action: suspend () -> Any?): JsonObject = try {
        val value = action()
        buildJsonObject { put("ok", true); put("data", Json.parseToJsonElement(gson.toJson(value))) }
    } catch (failure: CancellationException) { throw failure
    } catch (failure: Exception) {
        buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject {
                put("code", (failure as? TutorialProblem)?.code ?: "INVALID_STATE")
                put("field", (failure as? TutorialProblem)?.field ?: "")
                put("message", failure.message ?: "操作失败")
                (failure as? TutorialProblem)?.currentRevision?.let { put("currentRevision", it) }
            })
        }
    }

    protected fun importContext(service: TutorialService, context: UnderstandingContext, question: String = ""): TutorialContext {
        val sources = context.anchor?.let { listOf(registerSource(service, it).id) }.orEmpty()
        return service.store.saveContext(TutorialContext(context.token, question, sources, context.token))
    }

    protected fun registerSource(service: TutorialService, anchor: SourceAnchor): TutorialSource = service.store.saveSource(
        TutorialSource(newId("src"), anchor.path, anchor.text, anchor.text, 0, anchor.text.length, anchor.version),
    )

    protected fun graphResult(service: TutorialService, record: UnderstandingRecord): Map<String, Any?> = mapOf(
        "id" to record.id, "revision" to record.revision, "title" to (record.displayTitle ?: record.question), "parentId" to record.parentId,
        "graph" to TutorialGraph(
            record.graph.nodes.map { TutorialNode(it.id, it.label, it.anchor?.let { anchor -> registerSource(service, anchor).id }, it.kind, it.evidenceIds, it.flowRoles, it.endOutcome) },
            record.graph.edges.map { TutorialEdge(it.id, it.from, it.to, it.summary.orEmpty(), it.relation, it.condition, it.coverage, it.evidenceIds, it.inferred) },
            record.graph.evidence.map { TutorialEvidence(it.id, registerSource(service, it.anchor).id, it.note, it.dependencies.map { dependency -> registerSource(service, dependency).id }) },
            record.graph.clusters.map { TutorialCluster(it.id, it.title, it.nodeIds) },
        ),
    )

    protected fun page(items: List<Any>, limit: Int, cursor: String): Map<String, Any?> {
        tutorialRequire(limit in 1..100, "INVALID_LIMIT", "limit", "limit 需为 1..100")
        val start = if (cursor.isEmpty()) 0 else cursor.toIntOrNull() ?: throw TutorialProblem("INVALID_CURSOR", "cursor", "分页游标无效")
        tutorialRequire(start in 0..items.size, "INVALID_CURSOR", "cursor", "分页游标超出结果范围")
        val end = minOf(items.size, start + limit)
        return mapOf("items" to items.subList(start, end), "nextCursor" to if (end < items.size) end.toString() else null)
    }
}
