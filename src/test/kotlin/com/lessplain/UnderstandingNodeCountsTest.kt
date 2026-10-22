package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.NodeQuestionKey
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.cancelPendingQuestion
import com.lessplain.storage.consumeReadFeedback
import com.lessplain.storage.nodeQuestionCounts
import com.lessplain.storage.pendingNoteBatch
import com.lessplain.storage.pendingQuestionsForNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnderstandingNodeCountsTest {
    @TempDir lateinit var directory: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("n", "同名节点"), GraphNode("b", "其他节点")), emptyList())

    @Test fun `grouped counts match existing pending and answered ownership after updates`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val root = core.present(4, "root", "根", graph, "root")
            val other = core.present(4, "other", "其他理解", graph, "root")
            val context = core.captureContext(root.id, "n")
            val first = core.prepareQuestion("普通问题", context.token).question
            core.prepareQuestion("图表反馈", context.token)
            val second = core.prepareQuestion("另一个节点", core.captureContext(root.id, "b").token).question
            core.prepareQuestion("没有节点", core.captureContext(root.id).token)
            core.prepareQuestion("其他理解", core.captureContext(other.id, "n").token)
            val child = core.present(4, "child", first.text, graph, "child", questionId = first.id)
            core.prepareQuestion("追问中的注释", core.captureContext(child.id, "n").token)
            val counts = repository.nodeQuestionCounts(listOf(root.id, child.id, other.id, root.id))
            assertEquals(1, counts.pending[NodeQuestionKey(root.id, "n")])
            assertEquals(1, counts.pending[NodeQuestionKey(root.id, "b")])
            assertEquals(1, counts.pending[NodeQuestionKey(child.id, "n")])
            assertEquals(1, counts.pending[NodeQuestionKey(other.id, "n")])
            assertEquals(mapOf(NodeQuestionKey(root.id, "n") to 1), counts.answered)
            assertEquals(listOf(root, child, other).associate { it.id to it.revision }, counts.revisions)
            for (record in listOf(root, child, other)) for (node in record.graph.nodes) {
                assertEquals(repository.pendingQuestionsForNode(record.id, node.id).size,
                    counts.pending[NodeQuestionKey(record.id, node.id)] ?: 0)
            }
            repository.cancelPendingQuestion(second.id)
            repository.consumeReadFeedback(repository.pendingNoteBatch(root.id).items.filter { it.context.recordId == root.id })
            val updated = repository.nodeQuestionCounts(listOf(root.id, child.id))
            assertEquals(mapOf(NodeQuestionKey(child.id, "n") to 1), updated.pending)
            assertEquals(counts.answered, updated.answered)
            assertTrue(repository.nodeQuestionCounts(emptyList()).pending.isEmpty())
            assertTrue(repository.nodeQuestionCounts(listOf("missing")).revisions.isEmpty())
        }
    }

    @Test fun `large input is chunked and missing source node remains countable`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val root = core.present(4, "root", "根", graph, "root")
            val context = core.captureContext(root.id, "n").copy(token = "lost-node", nodeId = "deleted")
            repository.putContext(context)
            core.prepareQuestion("来源已经失效", context.token)
            val counts = repository.nodeQuestionCounts((0..205).map { "missing-$it" } + root.id)
            assertEquals(mapOf(NodeQuestionKey(root.id, "deleted") to 1), counts.pending)
            assertEquals(mapOf(root.id to 1), counts.revisions)
        }
    }
}
