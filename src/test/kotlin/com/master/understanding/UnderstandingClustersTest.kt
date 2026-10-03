package com.master.understanding

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.master.understanding.core.UnderstandingCore
import com.master.understanding.mcp.parseGraphPayload
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.model.validate
import com.master.understanding.storage.UnderstandingRepository
import com.master.understanding.storage.cancelPendingQuestion
import com.master.understanding.storage.pendingQuestionsForNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Protocol 3 remains supported without the new root-group field. */
class UnderstandingClustersTest {
    @TempDir lateinit var root: Path
    private val plain = UnderstandingGraph(listOf(GraphNode("a", "开始"), GraphNode("b", "结束")),
        listOf(GraphEdge("ab", "a", "b", "data_flow")))

    @Test fun `all retired cluster representations are rejected`() {
        val json = JsonParser.parseString(Gson().toJson(plain)).asJsonObject.apply { remove("clusters") }.toString()
        for (value in listOf("null", "[]", "[{\"id\":\"old\",\"title\":\"旧分组\",\"nodeIds\":[\"a\",\"b\"]}]")) {
            assertFailsWith<UnderstandingInputException> { parseGraphPayload(json.dropLast(1) + ",\"clusters\":$value}", 3) }
        }
        assertEquals(plain, parseGraphPayload(json))
    }

    @Test fun `business roles are explicit independent of kinds and degrees and persist through revisions`() {
        val graph = plain.copy(nodes = listOf(GraphNode("a", "开始", kind = "process", flowRoles = listOf("start", "end")),
            GraphNode("b", "普通叶节点", kind = "process")))
        graph.validate()
        assertEquals(graph, parseGraphPayload(Gson().toJson(graph)))
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            for (version in listOf(1, 2, 5)) assertFailsWith<UnderstandingInputException> {
                core.present(version, "wrong-$version", "流程", graph, "root")
            }
            val record = core.present(3, "new", "流程", graph, "root")
            assertEquals(graph, repository.getRecord(record.id).graph)
            val revised = core.revise(3, "revised", record.question, graph, record.id, record.revision)
            assertEquals(graph, revised.graph)
            assertEquals(record.revision + 1, revised.revision)
            assertEquals(record.id, revised.id)
        }
    }

    @Test fun `role parser deduplicates valid roles and rejects invalid values`() {
        val json = Gson().toJson(plain.copy(nodes = listOf(GraphNode("a", "步骤", flowRoles = listOf("start", "start", "end")),
            GraphNode("b", "普通"))))
        assertEquals(listOf("start", "end"), parseGraphPayload(json).nodes.first().flowRoles)
        for (value in listOf("success", "START", "")) assertFailsWith<UnderstandingInputException> {
            plain.copy(nodes = listOf(GraphNode("a", "步骤", flowRoles = listOf(value)), GraphNode("b", "普通"))).validate()
        }
        assertFailsWith<UnderstandingInputException> { parseGraphPayload(json.replace("start", "invalid")) }
    }

    @Test fun `cancelled notes reject late answers and keep source contexts`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(3, "root", "流程", plain, "root")
            val context = core.captureContext(record.id, "a")
            val cancelled = core.prepareQuestion("删掉这个问题", context.token).question
            val retained = core.prepareQuestion("保留这个问题", context.token).question
            repository.cancelPendingQuestion(cancelled.id)
            assertEquals(listOf(retained.id), repository.pendingQuestionsForNode(record.id, "a").map { it.id })
            assertEquals(context, repository.getContext(context.token))
            assertFailsWith<UnderstandingInputException> {
                core.present(3, "late", cancelled.text, plain, "child", questionId = cancelled.id)
            }
            assertFailsWith<UnderstandingInputException> { repository.cancelPendingQuestion(cancelled.id) }
            val child = core.present(3, "answer", retained.text, plain, "child", questionId = retained.id)
            assertFailsWith<UnderstandingInputException> { repository.cancelPendingQuestion(retained.id) }
            assertEquals(child, core.present(3, "answer", retained.text, plain, "child", questionId = retained.id))
            assertEquals(child, repository.getRecord(child.id))
        }
    }
}
