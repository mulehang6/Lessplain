package com.lessplain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lessplain.core.UnderstandingCore
import com.lessplain.mcp.UnderstandingToolset
import com.lessplain.mcp.parseGraphSubmission
import com.lessplain.mcp.understandingContextPayload
import com.lessplain.model.ClusterUpdate
import com.lessplain.model.GraphCluster
import com.lessplain.model.GraphNode
import com.lessplain.model.UNDERSTANDING_SCHEMA_VERSION
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.model.UnderstandingRecord
import com.lessplain.model.understandingSchemaRevision
import com.lessplain.model.validate
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.reviseRecordGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnderstandingSchemaTest {
    @TempDir lateinit var root: Path
    private val gson = Gson()
    private val graph = UnderstandingGraph(listOf(GraphNode("a", "步骤")),
        emptyList(), clusters = listOf(GraphCluster("all", "流程", listOf("a"))))

    @Test
    fun `public context handoff and MCP parameter use the same string version`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val context = core.captureContext()
            val payload = JsonParser.parseString(gson.toJson(understandingContextPayload(repository, context))).asJsonObject
            val copied = JsonParser.parseString(core.prepareQuestion("解释流程", context.token).payload).asJsonObject
            assertEquals("1.4", UNDERSTANDING_SCHEMA_VERSION)
            assertEquals(true, payload["schemaVersion"].asJsonPrimitive.isString)
            assertEquals(UNDERSTANDING_SCHEMA_VERSION, payload["schemaVersion"].asString)
            assertEquals(UNDERSTANDING_SCHEMA_VERSION, copied["protocolVersion"].asString)
            assertEquals(true, payload["capabilities"].asJsonObject["anchorValidation"].asBoolean)
            assertEquals(true, payload["capabilities"].asJsonObject["inferredEdges"].asBoolean)
            assertEquals(String::class.java, UnderstandingToolset::class.java.declaredMethods
                .single { it.name == "presentUnderstanding" }.parameterTypes.first())
        }
    }

    @Test
    fun `inferred edges use an explicit boolean with required coverage`() {
        val json = """{"nodes":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"edges":[{"id":"ab","from":"a","to":"b","relation":"call","inferred":true,"coverage":"由事件注册推断"}]}"""
        val parsed = parseGraphSubmission(json, 4).graph
        assertEquals(true, parsed.edges.single().inferred)
        parsed.validate()
        assertFailsWith<UnderstandingInputException> { parsed.copy(edges = parsed.edges.map { it.copy(coverage = null) }).validate() }
        assertFailsWith<UnderstandingInputException> { parseGraphSubmission(json.replace("\"inferred\":true", "\"inferred\":\"true\""), 4) }
        assertEquals(null, parseGraphSubmission(json.replace("\"inferred\":true,", ""), 4).graph.edges.single().inferred)
    }

    @Test
    fun `version identifiers have explicit capabilities and do not accept decimal approximations`() {
        assertEquals(4, understandingSchemaRevision("1.4"))
        assertEquals(4, understandingSchemaRevision("4"))
        assertEquals(3, understandingSchemaRevision("3"))
        for (version in listOf("", "1", "2", "1.3", "1.40", "1.10", "1.5", "1.4.0", " 1.4 ", "NaN")) {
            assertFailsWith<UnderstandingInputException> { understandingSchemaRevision(version) }
        }
        val json = gson.toJson(graph)
        assertEquals(graph, parseGraphSubmission(json, understandingSchemaRevision("1.4")).graph)
        assertFailsWith<UnderstandingInputException> { parseGraphSubmission(json, understandingSchemaRevision("3")) }
    }

    @Test
    fun `one point four replays exact legacy four create and revise payloads without conflicts`() {
        UnderstandingRepository(root).use { repository ->
            val original = UnderstandingRecord("old-record", 1, null, null, "流程", "", null, graph, 1)
            val oldCreate = gson.toJson(listOf(4, original.question, gson.toJsonTree(graph),
                "root", null, null, null, null))
            repository.request("old-create-v4", "present_understanding", oldCreate) { db ->
                repository.insertRecord(db, original)
                gson.toJson(original)
            }
            val revision = understandingSchemaRevision("1.4")
            val core = UnderstandingCore(repository)
            assertEquals(original, core.present(revision, "old-create-v4", original.question, graph, "root"))
            val changed = graph.copy(nodes = graph.nodes.map { it.copy(label = "更新步骤") })
            val update = ClusterUpdate.Replace(changed.clusters)
            val oldRevise = gson.toJson(listOf(4, original.question, gson.toJsonTree(changed),
                "revise", original.id, 1, update))
            repository.request("old-revise-v4", "present_understanding", oldRevise) { db ->
                gson.toJson(repository.reviseRecordGraph(db, original.id, 1, original.question, changed, update))
            }
            val revised = core.revise(revision, "old-revise-v4", original.question, changed, original.id, 1, update)
            assertEquals(2, revised.revision)
            assertEquals(changed, revised.graph)
            assertEquals(revised, core.revise(understandingSchemaRevision("4"), "old-revise-v4",
                original.question, changed, original.id, 1, update))
            assertFailsWith<UnderstandingInputException> {
                core.revise(revision, "old-revise-v4", original.question, changed, original.id, 1, ClusterUpdate.Keep)
            }
        }
    }
}
