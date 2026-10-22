package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.model.validate
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.cancelPendingQuestion
import com.lessplain.storage.pendingQuestionsForNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 当前图模型的业务角色、修订及问题保护。 */
class UnderstandingClustersTest {
    @TempDir lateinit var root: Path
    private val plain = UnderstandingGraph(listOf(GraphNode("a", "开始"), GraphNode("b", "结束")),
        listOf(GraphEdge("ab", "a", "b", "data_flow")))

    @Test fun `business roles are explicit independent of kinds and degrees and persist through revisions`() {
        val graph = plain.copy(nodes = listOf(GraphNode("a", "开始", kind = "process", flowRoles = listOf("start", "end")),
            GraphNode("b", "普通叶节点", kind = "process")))
        graph.validate()
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            for (version in listOf(1, 2, 3, 5)) assertFailsWith<UnderstandingInputException> {
                core.present(version, "wrong-$version", "流程", graph, "root")
            }
            val record = core.present(4, "new", "流程", graph, "root")
            assertEquals(graph, repository.getRecord(record.id).graph)
            val revised = core.revise(4, "revised", record.question, graph, record.id, record.revision)
            assertEquals(graph, revised.graph)
            assertEquals(record.revision + 1, revised.revision)
            assertEquals(record.id, revised.id)
        }
    }

    @Test fun `invalid business roles are rejected`() {
        for (value in listOf("success", "START", "")) assertFailsWith<UnderstandingInputException> {
            plain.copy(nodes = listOf(GraphNode("a", "步骤", flowRoles = listOf(value)), GraphNode("b", "普通"))).validate()
        }
    }

    @Test fun `cancelled notes reject late answers and keep source contexts`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "流程", plain, "root")
            val context = core.captureContext(record.id, "a")
            val cancelled = core.prepareQuestion("删掉这个问题", context.token).question
            val retained = core.prepareQuestion("保留这个问题", context.token).question
            repository.cancelPendingQuestion(cancelled.id)
            assertEquals(listOf(retained.id), repository.pendingQuestionsForNode(record.id, "a").map { it.id })
            assertEquals(context, repository.getContext(context.token))
            assertFailsWith<UnderstandingInputException> {
                core.present(4, "late", cancelled.text, plain, "child", questionId = cancelled.id)
            }
            assertFailsWith<UnderstandingInputException> { repository.cancelPendingQuestion(cancelled.id) }
            val child = core.present(4, "answer", retained.text, plain, "child", questionId = retained.id)
            assertFailsWith<UnderstandingInputException> { repository.cancelPendingQuestion(retained.id) }
            assertEquals(child, core.present(4, "answer", retained.text, plain, "child", questionId = retained.id))
            assertEquals(child, repository.getRecord(child.id))
        }
    }
}
