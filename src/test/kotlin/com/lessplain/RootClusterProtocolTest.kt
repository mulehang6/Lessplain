package com.lessplain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lessplain.core.UnderstandingCore
import com.lessplain.mcp.parseGraphPayload
import com.lessplain.mcp.parseGraphSubmission
import com.lessplain.mcp.understandingContextPayload
import com.lessplain.model.ClusterUpdate
import com.lessplain.model.GraphCluster
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.model.UnderstandingRecord
import com.lessplain.model.validate
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.reviseRecordGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RootClusterProtocolTest {
    @TempDir lateinit var root: Path
    private val plain = UnderstandingGraph(listOf(GraphNode("a", "入口"), GraphNode("b", "完成")),
        listOf(GraphEdge("ab", "a", "b", "call")))
    private val groups = listOf(GraphCluster("first", "入口阶段", listOf("a")), GraphCluster("last", "完成阶段", listOf("b")))
    private val grouped = plain.copy(clusters = groups)

    @Test fun `version four distinguishes omitted null and empty cluster updates`() {
        val omitted = JsonParser.parseString(Gson().toJson(plain)).asJsonObject.apply { remove("clusters") }.toString()
        assertEquals(ClusterUpdate.Keep, parseGraphSubmission(omitted, 4).clusterUpdate)
        assertEquals(ClusterUpdate.Keep, parseGraphSubmission(omitted.dropLast(1) + ",\"clusters\":null}", 4).clusterUpdate)
        assertEquals(ClusterUpdate.Replace(emptyList()), parseGraphSubmission(Gson().toJson(plain), 4).clusterUpdate)
        assertEquals(grouped, parseGraphPayload(Gson().toJson(grouped), 4))
        assertEquals(plain, parseGraphPayload(omitted, 3))
        assertFailsWith<UnderstandingInputException> { parseGraphPayload(Gson().toJson(plain), 3) }
    }

    @Test fun `groups must be a complete mutually exclusive single layer partition`() {
        grouped.validate()
        val invalid = listOf(
            groups.dropLast(1), groups + GraphCluster("extra", "重复成员", listOf("a")),
            groups.map { it.copy(id = "same") }, groups.map { it.copy(title = "") },
            groups.map { if (it.id == "first") it.copy(nodeIds = listOf("a", "missing")) else it },
            groups.map { if (it.id == "first") it.copy(nodeIds = listOf("a", "a")) else it },
        )
        invalid.forEach { clusters -> assertFailsWith<UnderstandingInputException> { plain.copy(clusters = clusters).validate() } }
        assertFailsWith<UnderstandingInputException> {
            parseGraphPayload(Gson().toJson(grouped).replace("\"title\":\"入口阶段\"", "\"title\":\"入口阶段\",\"children\":[]"), 4)
        }
    }

    @Test fun `root groups persist preserve replace and clear without rewriting old versions`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val original = core.present(4, "create", "流程", grouped, "root")
            val context = core.captureContext(original.id, "b", staticPath = listOf("ab"))
            val kept = core.revise(4, "keep", original.question, plain, original.id, 1, ClusterUpdate.Keep)
            assertEquals(groups, kept.graph.clusters)
            val replacement = listOf(GraphCluster("whole", "完整流程", listOf("a", "b")))
            val replaced = core.revise(4, "replace", original.question, plain.copy(clusters = replacement), original.id, 2)
            assertEquals(replacement, replaced.graph.clusters)
            val cleared = core.revise(4, "clear", original.question, plain, original.id, 3, ClusterUpdate.Replace(emptyList()))
            assertTrue(cleared.graph.clusters.isEmpty())
            assertEquals(cleared, core.revise(4, "clear", original.question, plain, original.id, 3, ClusterUpdate.Replace(emptyList())))
            assertFailsWith<UnderstandingInputException> {
                core.revise(4, "clear", original.question, plain, original.id, 3, ClusterUpdate.Keep)
            }
            assertEquals(context, repository.getContext(context.token))
            repository.connect().use { db -> db.prepareStatement("SELECT data FROM record_versions WHERE record_id=? AND revision=1").use {
                it.setString(1, original.id)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(groups.size, JsonParser.parseString(rows.getString(1)).asJsonObject
                        .getAsJsonObject("graph").getAsJsonArray("clusters").size())
                }
            } }
            val payload = understandingContextPayload(repository, context)
            assertEquals("1.4", payload["schemaVersion"])
            assertEquals(true, (payload["capabilities"] as Map<*, *>)["rootClusters"])
        }
    }

    @Test fun `child create and revise reject root grouping and leave existing records intact`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val original = core.present(3, "create", "流程", plain, "root")
            val context = core.captureContext(original.id, "a")
            assertFailsWith<UnderstandingInputException> { core.present(4, "child-invalid", "子问题", grouped, "child", context.token) }
            val child = core.present(4, "child", "子问题", plain, "child", context.token)
            assertFailsWith<UnderstandingInputException> { core.revise(4, "child-revise", child.question, grouped, child.id, 1) }
            assertEquals(child, repository.getRecord(child.id))
            assertFailsWith<UnderstandingInputException> { core.present(3, "v3-invalid", "流程", grouped, "root") }
        }
    }

    @Test fun `legacy records without clusters reopen with empty groups and keep the original blob`() {
        lateinit var recordId: String
        lateinit var oldJson: String
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "old", "旧流程", plain, "root")
            recordId = record.id
            oldJson = JsonParser.parseString(Gson().toJson(record)).asJsonObject.apply {
                getAsJsonObject("graph").remove("clusters")
            }.toString()
            repository.connect().use { db -> db.prepareStatement("UPDATE record_versions SET data=? WHERE record_id=?").use {
                it.setString(1, oldJson); it.setString(2, recordId); it.executeUpdate()
            } }
        }
        UnderstandingRepository(root).use { repository ->
            assertEquals(plain, repository.getRecord(recordId).graph)
            assertEquals(plain, repository.recentRecords().single().graph)
            repository.connect().use { db -> db.prepareStatement("SELECT data FROM record_versions WHERE record_id=?").use {
                it.setString(1, recordId)
                it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(oldJson, rows.getString(1)) }
            } }
        }
    }

    @Test fun `version three requests replay hashes and responses written before clusters existed`() {
        UnderstandingRepository(root).use { repository ->
            val gson = Gson()
            fun legacyGraph(graph: UnderstandingGraph) = gson.toJsonTree(graph).asJsonObject.apply { remove("clusters") }
            fun legacyRecord(record: UnderstandingRecord) = gson.toJsonTree(record).asJsonObject.apply {
                getAsJsonObject("graph").remove("clusters")
            }.toString()
            val original = UnderstandingRecord("old-record", 1, null, null, "旧流程", "", null, plain, 1)
            val oldCreate = gson.toJson(listOf(3, original.question, legacyGraph(plain), "root", null, null, null, null))
            repository.request("old-create", "present_understanding", oldCreate) { db ->
                repository.insertRecord(db, original)
                legacyRecord(original)
            }
            val core = UnderstandingCore(repository)
            assertEquals(original, core.present(3, "old-create", original.question, plain, "root"))
            val changed = plain.copy(nodes = plain.nodes.map { it.copy(label = "新${it.label}") })
            val oldRevision = gson.toJson(listOf(3, original.question, legacyGraph(changed), "revise", original.id, 1))
            val response = repository.request("old-revise", "present_understanding", oldRevision) { db ->
                legacyRecord(repository.reviseRecordGraph(db, original.id, 1, original.question, changed, ClusterUpdate.Keep))
            }
            val revised = core.revise(3, "old-revise", original.question, changed, original.id, 1)
            assertEquals(2, revised.revision)
            assertEquals(changed, revised.graph)
            assertEquals(response, legacyRecord(revised))
        }
    }
}
