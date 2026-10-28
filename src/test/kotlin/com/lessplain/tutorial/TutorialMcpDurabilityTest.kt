package com.lessplain.tutorial

import com.google.gson.Gson
import com.lessplain.model.ClusterUpdate
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.sha256
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class TutorialMcpDurabilityTest {
    @Test fun `returned sources contexts graph references and annotation contexts survive immediate reopen`() {
        TutorialMcpFixture("LessplainDurableReads").use { test ->
            val source = test.source()
            val sourceId = source["sourceId"]!!.jsonPrimitive.content
            TutorialStore(test.storeRoot).use { assertEquals(sourceId, it.source(sourceId).id) }
            val capture = test.call("lessplain_context_capture", buildJsonObject { put("question", "何时执行？") })
            val contextId = capture["contextId"]!!.jsonPrimitive.content
            TutorialStore(test.storeRoot).use { store ->
                assertEquals(contextId, store.context(contextId).id)
                store.context(contextId).sourceIds.forEach { store.source(it) }
            }
            val anchor = SourceAnchor("Flow.txt", null, "Flow<Event>", test.tutorials.store.source(sourceId).version)
            val graph = UnderstandingGraph(listOf(GraphNode("flow", "事件流", anchor = anchor)), emptyList())
            val saved = test.graphs.core.present(4, "durable-graph", "理解 Flow", graph, "root")
            val frozen = test.graphs.core.captureContext(saved.id, "flow", anchor)
            val context = test.call("lessplain_context_read", buildJsonObject { put("contextId", frozen.token) })
            val importedSource = context["context"]!!.jsonObject["sourceIds"]!!.jsonArray.single().jsonPrimitive.content
            TutorialStore(test.storeRoot).use { store -> assertEquals(frozen.token, store.context(frozen.token).id); store.source(importedSource) }
            val item = test.call("lessplain_item_read", buildJsonObject { put("id", saved.id) })
            val nodeSource = item["graph"]!!.jsonObject["nodes"]!!.jsonArray.single().jsonObject["sourceId"]!!.jsonPrimitive.content
            TutorialStore(test.storeRoot).use { it.source(nodeSource) }
            val questionContext = test.graphs.core.captureContext(saved.id, "flow", anchor)
            val question = test.graphs.core.prepareQuestion("为什么？", questionContext.token).question
            test.call("lessplain_item_read", buildJsonObject { put("id", question.id) })
            TutorialStore(test.storeRoot).use { it.context(questionContext.token) }
            val annotationContext = test.graphs.core.captureContext(saved.id, "flow", anchor)
            test.graphs.core.prepareQuestion("再次追问", annotationContext.token)
            test.call("lessplain_annotations_list", buildJsonObject { put("materialId", saved.id); put("status", "pending") })
            TutorialStore(test.storeRoot).use { store -> store.context(annotationContext.token).sourceIds.forEach { store.source(it) } }
        }
    }

    @Test fun `read tools report persistence failure instead of returning transient ids`() {
        TutorialMcpFixture("LessplainSourceReadFailure").use { test ->
            // source() 先建立真实编辑器；再用不同关注范围触发一次新引用保存。
            test.source()
            val blocked = test.storeRoot.resolve("sources.json.tmp")
            Files.createDirectory(blocked)
            try {
                val response = test.invoke("lessplain_source_resolve", buildJsonObject {
                    put("path", "Flow.txt"); put("context", "fun run(): Flow<Event> = TODO()"); put("focus", "Event")
                })
                assertTrue(response.isError)
                assertEquals("PERSISTENCE_FAILED", response.structuredContent!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertNull(response.structuredContent!!["data"])
            } finally { Files.delete(blocked) }
        }
        TutorialMcpFixture("LessplainContextReadFailure").use { test ->
            test.source()
            val blocked = test.storeRoot.resolve("contexts.json.tmp")
            Files.createDirectory(blocked)
            try {
                val response = test.invoke("lessplain_context_capture", buildJsonObject { put("question", "不能提前返回") })
                assertTrue(response.isError)
                assertEquals("PERSISTENCE_FAILED", response.structuredContent!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertNull(response.structuredContent!!["data"])
            } finally { Files.delete(blocked) }
        }
    }

    @Test fun `graph revision replays SQLite commit and reports genuine conflicts`() {
        TutorialMcpFixture("LessplainGraphRetry").use { test ->
            val sourceId = test.source()["sourceId"]!!.jsonPrimitive.content
            val contextId = test.call("lessplain_context_capture", buildJsonObject { put("question", "Flow") })["contextId"]!!.jsonPrimitive.content
            val graph = TutorialGraph(listOf(TutorialNode("flow", "原标签", sourceId)), emptyList())
            val gson = Gson()
            val created = test.call("lessplain_graph_create", Json.parseToJsonElement(gson.toJson(mapOf("contextId" to contextId, "title" to "Flow", "graph" to graph))).jsonObject)
            val id = created["id"]!!.jsonPrimitive.content
            val revised = graph.copy(nodes = listOf(graph.nodes.single().copy(label = "新标签")))
            val prepared = revised.toUnderstanding(test.tutorials.sources::anchor)
            val record = test.graphs.repository.getRecord(id)
            test.graphs.core.revise(4, sha256(gson.toJson(listOf("graph_revise", id, 1, revised))), record.question, prepared, id, 1, ClusterUpdate.Replace(prepared.clusters))
            val response = test.call("lessplain_graph_revise", Json.parseToJsonElement(gson.toJson(mapOf("id" to id, "expectedRevision" to 1, "graph" to revised))).jsonObject)
            assertEquals(2, response["revision"]!!.jsonPrimitive.int)
            assertEquals(2, test.graphs.repository.getRecord(id).revision)
            val replay = test.call("lessplain_graph_revise", Json.parseToJsonElement(gson.toJson(mapOf("id" to id, "expectedRevision" to 1, "graph" to revised))).jsonObject)
            assertEquals(response, replay, "相同请求通过 SQLite 回执重放，不另建教程缓存")
            val changed = revised.copy(nodes = listOf(revised.nodes.single().copy(label = "另一个修订")))
            val conflict = test.invoke("lessplain_graph_revise", Json.parseToJsonElement(gson.toJson(mapOf("id" to id, "expectedRevision" to 1, "graph" to changed))).jsonObject)
            assertTrue(conflict.isError)
            val error = conflict.structuredContent!!["error"]!!.jsonObject
            assertEquals("REVISION_CONFLICT", error["code"]!!.jsonPrimitive.content)
            assertEquals("expectedRevision", error["field"]!!.jsonPrimitive.content)
            assertEquals(2, error["currentRevision"]!!.jsonPrimitive.int)
        }
    }
    @Test fun `deduplicated creation retains its original reply if the global receipt write fails`() {
        TutorialMcpFixture("LessplainDuplicateCreationReceipt").use { test ->
            val service = test.tutorials
            service.store.saveContext(TutorialContext("ctx_duplicate", "来源", emptyList()))
            val record = service.create("ctx_duplicate", "已有教程", "原正文", TutorialResourceState())
            service.store.flush()
            val arguments = buildJsonObject { put("contextId", "ctx_duplicate"); put("title", "已有教程"); put("markdown", "原正文") }
            val key = sha256(Gson().toJson(listOf("tutorial_create", listOf("ctx_duplicate", "已有教程", "原正文", emptyList<TutorialDiagram>(), emptyList<TutorialSimulation>()))))
            val blocked = test.storeRoot.resolve("tool-receipts.json.tmp")
            Files.createDirectory(blocked)
            try {
                val failed = test.invoke("lessplain_tutorial_create", arguments)
                assertTrue(failed.isError)
                assertEquals("PERSISTENCE_FAILED", failed.structuredContent?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content)
                TutorialStore(test.storeRoot).use { reader ->
                    val reply = assertNotNull(reader.toolReceipt(key), "复用既有教程时，也应随正文保存该请求的原始回执")
                    assertEquals("原正文", Json.parseToJsonElement(reply).jsonObject["data"]!!.jsonObject["tutorial"]!!.jsonObject["markdown"]!!.jsonPrimitive.content)
                }
            } finally { Files.delete(blocked) }
            service.store.change(record.id, record.revision, "修改后正文", 0, record.markdown.length, "修改后正文".length)
            service.store.flush()
            TutorialStore(test.storeRoot).use { reader ->
                val reply = assertNotNull(reader.toolReceipt(key))
                assertEquals("原正文", Json.parseToJsonElement(reply).jsonObject["data"]!!.jsonObject["tutorial"]!!.jsonObject["markdown"]!!.jsonPrimitive.content)
                assertEquals("修改后正文", reader.record(record.id).markdown)
            }
        }
    }

    @Test fun `cached answer retries still require a durable checkpoint before returning success`() {
        TutorialMcpFixture("LessplainCachedAnswerDurability").use { test ->
            val service = test.tutorials
            service.store.saveContext(TutorialContext("ctx_retry", "来源", emptyList()))
            val record = service.create("ctx_retry", "回执", "正文", TutorialResourceState())
            val question = service.annotationDraft(record.id, 0, 2, "question").copy(text = "为什么", status = "pending")
            service.store.createAnnotation(question); service.store.flush()
            val blocked = test.storeRoot.resolve(record.id).resolve("content.json.tmp")
            Files.createDirectory(blocked)
            val args = buildJsonObject { put("questionId", question.id); put("markdown", "原始答案") }
            try {
                repeat(2) {
                    val failed = test.invoke("lessplain_question_answer", args)
                    assertTrue(failed.isError, "缓存中的未持久化回执不能提前返回成功")
                    assertEquals("PERSISTENCE_FAILED", failed.structuredContent?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content)
                }
            } finally { Files.delete(blocked) }
            val saved = test.call("lessplain_question_answer", args)
            assertEquals("原始答案", saved["answer"]?.jsonPrimitive?.content)
            TutorialStore(test.storeRoot).use { reader -> assertEquals("原始答案", reader.annotation(question.id).answer) }
        }
    }
}
