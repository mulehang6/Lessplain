package com.master.lessplain

import com.google.gson.Gson
import com.master.lessplain.core.UnderstandingCore
import com.master.lessplain.mcp.parseGraphPayload
import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingInputException
import com.master.lessplain.model.branchNodeIds
import com.master.lessplain.model.validate
import com.master.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NodeSemanticsTest {
    @TempDir lateinit var root: Path

    @Test fun `removed decision is an ignored field and end outcomes parse strictly`() {
        val legacy = """{"nodes":[{"id":"old","label":"结束","flowRoles":["end"]}],"edges":[]}"""
        val old = parseGraphPayload(legacy).nodes.single()
        assertNull(old.endOutcome)
        val marked = legacy.replace("\"label\":\"结束\"", "\"label\":\"结束\",\"decision\":true")
        assertEquals(old, parseGraphPayload(marked).nodes.single())
        val stored = Gson().toJson(UnderstandingGraph(listOf(old), emptyList()))
            .replace("\"label\":\"结束\"", "\"label\":\"结束\",\"decision\":true")
        assertEquals(old, Gson().fromJson(stored, UnderstandingGraph::class.java).nodes.single())
        assertFalse(Gson().toJson(parseGraphPayload(marked)).contains("decision"))
        assertEquals(emptySet(), parseGraphPayload(marked).branchNodeIds())
        for (outcome in listOf("success", "failure")) {
            val node = parseGraphPayload(legacy.replace("\"label\":\"结束\"", "\"label\":\"结束\",\"decision\":true,\"endOutcome\":\"$outcome\"")).also { it.validate() }.nodes.single()
            assertEquals(outcome, node.endOutcome)
        }
        for (value in listOf("true", "1", "[]", "{}")) {
            assertFailsWith<UnderstandingInputException> {
                parseGraphPayload(legacy.replace("\"label\":\"结束\"", "\"label\":\"结束\",\"endOutcome\":$value"))
            }
        }
        for (node in listOf(old.copy(endOutcome = "unknown"), old.copy(flowRoles = emptyList(), endOutcome = "success"))) {
            assertFailsWith<UnderstandingInputException> { UnderstandingGraph(listOf(node), emptyList()).validate() }
        }
    }

    @Test fun `semantics persist through revision without rebinding a pending node question`() {
        val base = UnderstandingGraph(listOf(GraphNode("entry", "入口", flowRoles = listOf("start")),
            GraphNode("done", "成功", flowRoles = listOf("end")), GraphNode("refused", "拒绝", flowRoles = listOf("end"))),
            listOf(GraphEdge("yes", "entry", "done", "control_flow", "允许"),
                GraphEdge("no", "entry", "refused", "control_flow", "拒绝")))
        lateinit var recordId: String
        lateinit var token: String
        lateinit var questionId: String
        val classified = base.copy(nodes = base.nodes.map { node -> when (node.id) {
            "entry" -> node
            "done" -> node.copy(endOutcome = "success")
            else -> node.copy(endOutcome = "failure")
        } })
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(3, "create", "原问题", base, "root")
            recordId = record.id
            token = core.captureContext(recordId, "entry").token
            questionId = core.prepareQuestion("为什么会拒绝？", token).question.id
            val revised = core.revise(3, "classify", record.question, classified, recordId, record.revision)
            assertEquals(2, revised.revision)
            assertEquals(base.edges, revised.graph.edges)
            assertEquals(base.nodes.map { it.id }, revised.graph.nodes.map { it.id })
            assertEquals(recordId, revised.id)
        }
        UnderstandingRepository(root).use { repository ->
            assertEquals(classified, repository.getRecord(recordId).graph)
            assertEquals("entry", repository.getContext(token).nodeId)
            assertEquals(1, repository.getContext(token).recordRevision)
            assertEquals(token, repository.getQuestion(questionId).contextToken)
            assertEquals("为什么会拒绝？", repository.getQuestion(questionId).text)
        }
    }
}
