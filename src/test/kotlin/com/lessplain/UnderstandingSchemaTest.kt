package com.lessplain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lessplain.core.UnderstandingCore
import com.lessplain.mcp.LessplainMaterialTools
import com.lessplain.mcp.understandingContextPayload
import com.lessplain.model.*
import com.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class UnderstandingSchemaTest {
    @TempDir lateinit var root: Path
    private val gson = Gson()
    @Test
    fun `context handoff preserves frozen identity and MCP uses structured graph DTO`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val context = core.captureContext()
            val payload = JsonParser.parseString(gson.toJson(understandingContextPayload(repository, context))).asJsonObject
            val copied = JsonParser.parseString(core.prepareQuestion("解释流程", context.token).payload).asJsonObject
            assertEquals("1.4", UNDERSTANDING_SCHEMA_VERSION)
            assertEquals(true, payload["schemaVersion"].asJsonPrimitive.isString)
            assertEquals(UNDERSTANDING_SCHEMA_VERSION, payload["schemaVersion"].asString)
            assertEquals(context.token, copied["contextId"].asString)
            assertEquals(true, payload["capabilities"].asJsonObject["anchorValidation"].asBoolean)
            assertEquals(true, payload["capabilities"].asJsonObject["inferredEdges"].asBoolean)
            assertEquals(true, payload["capabilities"].asJsonObject["edgeSummaries"].asBoolean)
            assertEquals(com.lessplain.tutorial.TutorialGraph::class.java, LessplainMaterialTools::class.java.declaredMethods
                .single { it.name == "graphCreate" }.parameterTypes[2])
        }
    }


    @Test fun `inferred edges require coverage and summaries persist with complete evidence`() {
        val graph = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")),
            listOf(GraphEdge("ab", "a", "b", "call", coverage = "完整依据", inferred = true, summary = "传递结果")))
        graph.validate()
        assertFailsWith<UnderstandingInputException> { graph.copy(edges = graph.edges.map { it.copy(coverage = null) }).validate() }
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(4, "summary", "关系", graph, "root")
            assertEquals(graph, repository.getRecord(record.id).graph)
        }
    }
}
