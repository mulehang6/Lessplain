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
    protected suspend fun writeResult(operation: String, arguments: Any, useTutorialReceipt: Boolean = true, targetId: String? = null, action: suspend (String) -> Any?): McpToolCallResult {
        // 图库拥有事务内回执和删除墓碑，不能由教程缓存绕过它们。
        if (!useTutorialReceipt) return durableResult { action("") }
        return response(resultEnvelope {
            val store = tutorial().store
            val key = com.lessplain.storage.sha256(gson.toJson(listOf(operation, arguments)))
            store.registerToolWrite(key, targetId)
            store.toolReceipt(key)?.let {
                store.flush()
                val reply = store.toolReceipt(key) ?: throw TutorialProblem("NOT_FOUND", "id", "写入结果已被删除")
                return@resultEnvelope Json.parseToJsonElement(reply) as JsonObject
            }
            val value = action(key)
            store.flush()
            val saved = success(value)
            store.saveToolReceipt(key, saved.toString())
            store.flush()
            val reply = store.toolReceipt(key) ?: throw TutorialProblem("NOT_FOUND", "id", "写入结果已被删除，无法完成答复")
            Json.parseToJsonElement(reply) as JsonObject
        })
    }
    /** 冻结来源或上下文的读取也必须在成功返回 ID 前落盘；反馈消费独立使用 SQLite。 */
    protected suspend fun durableResult(action: suspend () -> Any?): McpToolCallResult = result {
        action().also { tutorial().store.flush() }
    }
    protected suspend fun result(action: suspend () -> Any?): McpToolCallResult = response(resultObject(action))
    private fun response(value: JsonObject) = McpToolCallResult(arrayOf(McpToolCallResultContent.Text(value.toString())), value, value["ok"].toString() != "true")
    private suspend fun resultObject(action: suspend () -> Any?): JsonObject = resultEnvelope { success(action()) }
    private suspend fun resultEnvelope(action: suspend () -> JsonObject): JsonObject = try {
        action()
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

    private fun success(value: Any?): JsonObject = buildJsonObject {
        put("ok", true); put("data", Json.parseToJsonElement(gson.toJson(value)))
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

    protected fun pageStart(limit: Int, cursor: String): Int {
        tutorialRequire(limit in 1..100, "INVALID_LIMIT", "limit", "limit 需为 1..100")
        val start = if (cursor.isEmpty()) 0 else cursor.toIntOrNull() ?: throw TutorialProblem("INVALID_CURSOR", "cursor", "分页游标无效")
        tutorialRequire(start >= 0, "INVALID_CURSOR", "cursor", "分页游标无效")
        return start
    }

    protected fun page(items: List<Any>, total: Int, start: Int): Map<String, Any?> {
        tutorialRequire(start <= total, "INVALID_CURSOR", "cursor", "分页游标超出结果范围")
        val end = start + items.size
        return mapOf("items" to items, "nextCursor" to if (end < total) end.toString() else null)
    }
}
