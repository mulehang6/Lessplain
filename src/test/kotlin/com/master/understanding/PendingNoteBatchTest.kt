package com.master.understanding

import com.master.understanding.core.UnderstandingCore
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.storage.UnderstandingRepository
import com.master.understanding.storage.cancelPendingQuestion
import com.master.understanding.storage.deleteRootRecord
import com.master.understanding.storage.editPendingQuestion
import com.master.understanding.storage.pendingNoteBatch
import com.master.understanding.storage.pendingQuestionsForNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PendingNoteBatchTest {
    @TempDir lateinit var root: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("n", "步骤")), emptyList())

    @Test fun `batch includes descendants in stable order and excludes other diagrams and finished notes`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val first = core.present(3, "root", "第一张", graph, "root")
            val second = core.present(3, "other", "另一张", graph, "root")
            val context = core.captureContext(first.id, "n")
            val answered = core.prepareQuestion("已答", context.token).question
            val child = core.present(3, "answer", answered.text, graph, "child", questionId = answered.id)
            val a = core.prepareQuestion("根图注释", context.token).question
            val b = core.prepareQuestion("追问注释", core.captureContext(child.id, "n").token).question
            val nested = core.present(3, "nested", "更深问题", graph, "child", core.captureContext(child.id, "n").token)
            val c = core.prepareQuestion("更深注释", core.captureContext(nested.id, "n").token).question
            val cancelled = core.prepareQuestion("已取消", context.token).question
            repository.cancelPendingQuestion(cancelled.id)
            core.prepareQuestion("其他图注释", core.captureContext(second.id, "n").token)
            val batch = repository.pendingNoteBatch(first.id)
            assertEquals(first.id, batch.scopeRecordId)
            assertEquals(listOf(a.id, b.id, c.id), batch.items.map { it.questionId })
            assertEquals(listOf(a.contextToken, b.contextToken, c.contextToken), batch.items.map { it.context.token })
            assertEquals(listOf(first.id, child.id, nested.id), batch.records.map { it.id })
            assertEquals(listOf(b.id, c.id), repository.pendingNoteBatch(child.id).items.map { it.questionId })
            assertFailsWith<UnderstandingInputException> { repository.pendingNoteBatch("missing") }
        }
    }

    @Test fun `successful answer deletes independent note but retains history deduplication and fixed source`() {
        lateinit var rootId: String
        lateinit var questionId: String
        lateinit var answerId: String
        lateinit var token: String
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            rootId = core.present(3, "root", "主图", graph, "root").id
            token = core.captureContext(rootId, "n").token
            val note = core.prepareQuestion("为什么？", token).question
            questionId = note.id
            val answer = core.present(3, "answer", note.text, graph, "child", questionId = note.id)
            answerId = answer.id
            assertTrue(repository.pendingQuestionsForNode(rootId, "n").isEmpty())
            assertEquals(0, noteRowCount(repository, questionId))
            assertEquals(answer, core.present(3, "answer", note.text, graph, "child", questionId = note.id))
            assertFailsWith<UnderstandingInputException> {
                core.present(3, "duplicate", note.text, graph, "child", questionId = note.id)
            }
            assertEquals(token, repository.getQuestion(questionId).contextToken)
            assertEquals("answered", repository.getQuestion(questionId).status)
            assertEquals(questionId, repository.questionsForRecord(rootId).single().id)
            assertEquals(questionId, repository.questionsForContext(token).single().id)
        }
        UnderstandingRepository(root).use { repository ->
            assertEquals(answerId, repository.recordForQuestion(questionId)?.id)
            assertEquals("n", repository.getContext(repository.getQuestion(questionId).contextToken).nodeId)
            assertTrue(repository.pendingNoteBatch(rootId).items.isEmpty())
            repository.deleteRootRecord(rootId)
            assertFailsWith<UnderstandingInputException> { repository.getQuestion(questionId) }
        }
    }

    @Test fun `partial batch failure and changed note preserve unresolved questions`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(3, "root", "主图", graph, "root")
            val context = core.captureContext(record.id, "n")
            val a = core.prepareQuestion("第一个", context.token).question
            val b = core.prepareQuestion("第二个", context.token).question
            core.present(3, "a", a.text, graph, "child", questionId = a.id)
            repository.editPendingQuestion(b.id, "修改后的第二个")
            assertFailsWith<UnderstandingInputException> { core.present(3, "b", b.text, graph, "child", questionId = b.id) }
            assertFailsWith<UnderstandingInputException> {
                core.present(3, "invalid", "修改后的第二个", UnderstandingGraph(emptyList(), emptyList()), "child", questionId = b.id)
            }
            assertEquals(listOf(b.id), repository.pendingNoteBatch(record.id).items.map { it.questionId })
            assertEquals("修改后的第二个", repository.pendingNoteBatch(record.id).items.single().question)
            assertEquals(1, noteRowCount(repository, b.id))
            assertEquals(0, noteRowCount(repository, a.id))
        }
    }

    private fun noteRowCount(repository: UnderstandingRepository, id: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${repository.databasePath}").use { db ->
            db.prepareStatement("SELECT COUNT(*) FROM questions WHERE id=?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
}
