package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.deleteRootRecord
import com.lessplain.storage.editPendingQuestion
import com.lessplain.storage.pendingQuestionsForNode
import com.lessplain.storage.updateDisplayTitle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnderstandingRecordMutationsTest {
    @TempDir lateinit var root: Path

    private val graph = UnderstandingGraph(
        listOf(GraphNode("request", "请求参数"), GraphNode("value", "读取到的值")),
        listOf(GraphEdge("read", "request", "value", "data_flow")),
    )

    @Test
    fun `saving requires a real graph and a title revision retains the original question`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            assertFailsWith<UnderstandingInputException> {
                core.present(4, "empty", "getParameter 如何取值？", UnderstandingGraph(emptyList(), emptyList()), "root")
            }
            assertFailsWith<UnderstandingInputException> {
                core.present(4, "placeholder", "getParameter 如何取值？",
                    UnderstandingGraph(listOf(GraphNode("waiting", "等待", "placeholder")), emptyList()), "root")
            }
            val saved = core.present(4, "graph", "getParameter 如何取值？", graph, "root",
                displayTitle = "读取请求参数")
            assertEquals("", saved.answer)
            assertEquals("读取请求参数", saved.displayTitle)
            assertFailsWith<UnderstandingInputException> { repository.updateDisplayTitle(saved.id, "   ") }
            val updated = repository.updateDisplayTitle(saved.id, "参数值从哪里来")
            assertEquals(2, updated.revision)
            assertEquals(saved.question, updated.question)
            assertEquals(saved.graph, updated.graph)
            assertEquals("参数值从哪里来", repository.getRecord(saved.id).displayTitle)
        }
        UnderstandingRepository(root).use { reopened ->
            assertEquals("参数值从哪里来", reopened.recentRootRecords().single().displayTitle)
            assertEquals(2, reopened.getRecord(reopened.recentRootRecords().single().id).revision)
        }
    }

    @Test
    fun `pending node note can change until a graph answers it`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val rootRecord = core.present(4, "root", "参数怎么读取", graph, "root")
            val context = core.captureContext(rootRecord.id, "request")
            val note = core.prepareQuestion("为什么需要这个参数？", context.token).question
            assertEquals(note.id, repository.pendingQuestionsForNode(rootRecord.id, "request").single().id)
            val edited = repository.editPendingQuestion(note.id, "缺少 username 时会怎样？")
            assertEquals("缺少 username 时会怎样？", edited.text)
            val child = core.present(4, "child", edited.text, graph, "child", questionId = note.id)
            assertEquals(rootRecord.id, child.parentId)
            assertTrue(repository.pendingQuestionsForNode(rootRecord.id, "request").isEmpty())
            assertEquals("answered", repository.getQuestion(note.id).status)
            assertFailsWith<UnderstandingInputException> { repository.editPendingQuestion(note.id, "再改") }
        }
    }

    @Test
    fun `deleting a root removes descendants and rejects replay after restart`() {
        var rootId = ""
        var questionId = ""
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val parent = core.present(4, "root-write", "参数流向", graph, "root")
            rootId = parent.id
            val context = core.captureContext(parent.id, "request")
            val note = core.prepareQuestion("值从何而来？", context.token).question
            questionId = note.id
            core.present(4, "child-write", note.text, graph, "child", questionId = note.id)
            val removed = repository.deleteRootRecord(parent.id)
            assertEquals(2, removed.recordIds.size)
            assertTrue(questionId in removed.questionIds)
            assertTrue(repository.recentRootRecords().isEmpty())
            assertFailsWith<UnderstandingInputException> {
                core.present(4, "root-write", "参数流向", graph, "root")
            }
        }
        UnderstandingRepository(root).use { reopened ->
            assertTrue(reopened.recentRootRecords().isEmpty())
            assertFailsWith<UnderstandingInputException> { reopened.getRecord(rootId) }
            assertFailsWith<UnderstandingInputException> { reopened.getQuestion(questionId) }
            assertFalse(reopened.readOnly)
        }
    }
}
