package com.master.lessplain

import com.google.gson.Gson
import com.master.lessplain.core.UnderstandingCore
import com.master.lessplain.mcp.understandingContextPayload
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnderstandingContextPayloadTest {
    @TempDir lateinit var root: Path

    @Test fun `context wire queue exposes every fixed source and can be rechecked independently of selection`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val graph = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")), emptyList())
            val record = core.present(3, "root", "主图", graph, "root")
            val a = core.prepareQuestion("问题A", core.captureContext(record.id, "a").token).question
            val b = core.prepareQuestion("问题B", core.captureContext(record.id, "b").token).question
            val gson = Gson()
            val wire = gson.toJsonTree(understandingContextPayload(repository, core.captureContext(record.id, "b"))).asJsonObject
            assertTrue(wire["capabilities"].asJsonObject["pendingNoteBatch"].asBoolean)
            assertFalse(wire.has("pendingQuestionId"))
            val batch = wire["pendingBatch"].asJsonObject
            assertEquals(record.id, batch["scopeRecordId"].asString)
            assertEquals(listOf(a.id, b.id), batch["items"].asJsonArray.map { it.asJsonObject["questionId"].asString })
            assertEquals(listOf("a", "b"), batch["items"].asJsonArray.map { it.asJsonObject["context"].asJsonObject["nodeId"].asString })
            val fixed = gson.toJsonTree(understandingContextPayload(repository, core.context(a.contextToken))).asJsonObject
            assertEquals(a.id, fixed["pendingQuestionId"].asString)
            core.present(3, "a", a.text, graph, "child", questionId = a.id)
            val check = gson.toJsonTree(understandingContextPayload(repository, core.captureContext(record.id))).asJsonObject
            assertEquals(listOf(b.id), check["pendingBatch"].asJsonObject["items"].asJsonArray.map { it.asJsonObject["questionId"].asString })
        }
    }

    @Test fun `source without diagram never falls back to another diagrams pending notes`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(3, "root", "主图", UnderstandingGraph(listOf(GraphNode("a", "A")), emptyList()), "root")
            core.prepareQuestion("未答", core.captureContext(record.id, "a").token)
            val wire = Gson().toJsonTree(understandingContextPayload(repository, core.captureContext())).asJsonObject
            assertFalse(wire.has("pendingBatch"))
            assertFalse(wire.has("record"))
        }
    }
}
