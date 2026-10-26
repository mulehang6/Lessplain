package com.lessplain.tutorial

import com.lessplain.storage.deleteRootRecord
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 通过真实 MCP 工具反射器检查外层缓存不能绕过图库的删除墓碑。 */
class GraphMcpLifetimeTest {
    @Test fun `deleted graphs and consumed feedback cannot be recovered through cached tool replies`() {
        TutorialMcpFixture("GraphMcpLifetime").use { fixture ->
            val source = fixture.source()
            val sourceId = source.getValue("sourceId").jsonPrimitive.content
            val context = fixture.graphs.core.captureContext()
            val create = buildJsonObject {
                put("contextId", context.token); put("title", "测试图")
                put("graph", buildJsonObject {
                    put("nodes", buildJsonArray { add(buildJsonObject { put("id", "n"); put("label", "节点"); put("sourceId", sourceId) }) })
                    put("edges", buildJsonArray {})
                })
            }
            val created = fixture.call("lessplain_graph_create", create)
            val id = created.getValue("id").jsonPrimitive.content
            val revise = buildJsonObject {
                put("id", id); put("expectedRevision", 1); put("graph", create.getValue("graph"))
            }
            fixture.call("lessplain_graph_revise", revise)
            val frozen = fixture.graphs.core.captureContext(id, "n")
            val question = fixture.graphs.core.prepareQuestion("测试反馈", frozen.token).question
            val feedback = buildJsonObject { put("ids", buildJsonArray { add(question.id) }) }
            fixture.call("lessplain_feedback_consume", feedback)
            fixture.graphs.repository.deleteRootRecord(id)
            assertTrue(fixture.invoke("lessplain_graph_create", create).isError)
            assertTrue(fixture.invoke("lessplain_graph_revise", revise).isError)
            assertTrue(fixture.invoke("lessplain_feedback_consume", feedback).isError)
            assertTrue(fixture.graphs.repository.recentRecords().isEmpty())
        }
    }
    @Test fun `invalid cached replies use the structured MCP error envelope`() {
        TutorialMcpFixture("LessplainClosedMcpStore").use { fixture ->
            val key = com.lessplain.storage.sha256(com.google.gson.Gson().toJson(listOf("tutorial_create", listOf("ctx_missing", "标题", "正文", emptyList<Any>(), emptyList<Any>()))))
            val receipts = TutorialStore::class.java.getDeclaredField("toolReceipts").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val cache = receipts.get(fixture.tutorials.store) as MutableMap<String, String>
            cache[key] = "corrupt-json"
            val result = fixture.invoke("lessplain_tutorial_create", buildJsonObject {
                put("contextId", "ctx_missing"); put("title", "标题"); put("markdown", "正文")
            })
            assertTrue(result.isError)
            assertFalse(result.structuredContent!!.getValue("ok").jsonPrimitive.boolean)
            assertNotNull(result.structuredContent!!.getValue("error").jsonObject["code"])
        }
    }

}
