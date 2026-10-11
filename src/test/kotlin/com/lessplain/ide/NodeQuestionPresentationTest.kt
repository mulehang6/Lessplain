package com.lessplain.ide

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.editPendingQuestion
import com.lessplain.storage.pendingNoteBatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NodeQuestionPresentationTest {
    @TempDir lateinit var root: Path
    private val anchor = SourceAnchor("Snapshot.kt", "run", "    unsaved(中文)\n", "document-version", 45)
    private val graph = UnderstandingGraph(listOf(GraphNode("a", "同名节点", anchor = anchor),
        GraphNode("b", "同名节点")), emptyList())

    @Test fun `saved selection is shown from its own context after source edits and graph changes`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "create", "流程", graph, "root")
            val selected = anchor.copy(text = "    selected(<中文>)")
            val question = core.prepareQuestion("为什么这样写？", core.captureContext(record.id, "a", selected).token).question
            core.captureContext(record.id, "b", anchor.copy(text = "另一个选区"))
            Files.writeString(root.resolve(anchor.path), "现在的源码已经改变")
            core.revise(4, "revise", record.question, graph.copy(nodes = graph.nodes.map {
                if (it.id == "a") it.copy(label = "重新命名", anchor = anchor.copy(text = "新的节点来源")) else it
            }), record.id, 1)
            val presentation = nodeQuestionPresentation(repository, record.id, "a", question)
            assertEquals("提问 · 重新命名", presentation.title)
            assertEquals(selected, presentation.anchor)
            val edited = repository.editPendingQuestion(question.id, "修改后的问题")
            assertEquals(presentation, nodeQuestionPresentation(repository, record.id, "a", edited))
            val batch = repository.pendingNoteBatch(record.id).items.single()
            assertEquals("修改后的问题", batch.question)
            assertEquals("a", batch.context.nodeId)
            assertEquals(selected, batch.context.anchor)
            assertFailsWith<IllegalStateException> { nodeQuestionPresentation(repository, record.id, "b", edited) }
        }
    }

    @Test fun `questions without source fragments remain plain and answer processing stays unchanged`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "create", "流程", graph, "root")
            val question = core.prepareQuestion("普通问题", core.captureContext(record.id, "b").token).question
            assertNull(nodeQuestionPresentation(repository, record.id, "b", question).anchor)
            val answered = core.present(4, "answer", question.text, graph, "child", questionId = question.id)
            assertEquals(record.id, answered.parentId)
            assertTrue(repository.pendingNoteBatch(record.id).items.isEmpty())
        }
    }
}
