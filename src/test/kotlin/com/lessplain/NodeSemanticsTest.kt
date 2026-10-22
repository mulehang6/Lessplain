package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.model.validate
import com.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NodeSemanticsTest {
    @TempDir lateinit var root: Path

    @Test fun `end outcomes are explicit and require end roles`() {
        val node = GraphNode("end", "结束", flowRoles = listOf("end"))
        assertNull(node.endOutcome)
        for (outcome in listOf("success", "failure")) UnderstandingGraph(listOf(node.copy(endOutcome = outcome)), emptyList()).validate()
        for (bad in listOf(node.copy(endOutcome = "unknown"), node.copy(flowRoles = emptyList(), endOutcome = "success"))) {
            assertFailsWith<UnderstandingInputException> { UnderstandingGraph(listOf(bad), emptyList()).validate() }
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
            val record = core.present(4, "create", "原问题", base, "root")
            recordId = record.id
            token = core.captureContext(recordId, "entry").token
            questionId = core.prepareQuestion("为什么会拒绝？", token).question.id
            val revised = core.revise(4, "classify", record.question, classified, recordId, record.revision)
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
