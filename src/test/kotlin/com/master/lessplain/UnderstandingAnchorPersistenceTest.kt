package com.master.lessplain

import com.master.lessplain.core.UnderstandingCore
import com.master.lessplain.ide.AnchorSourceSnapshot
import com.master.lessplain.ide.validateGraphAnchors
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.SourceAnchor
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingInputException
import com.master.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnderstandingAnchorPersistenceTest {
    @TempDir lateinit var root: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("save", "保存", anchor =
        SourceAnchor("src/A.kt", "submit", "fun submit() { save() }", ""))), emptyList())

    @Test
    fun `preparation is outside write transaction and rechecks a request committed during preparation`() {
        UnderstandingRepository(root).use { repository ->
            val result = repository.requestPrepared("same", "operation", "payload", {
                repository.request("same", "operation", "payload") { "first-result" }
            }) { _, _ -> error("准备期间已有同请求结果，不能再次写入") }
            assertEquals("first-result", result)
            val replay = repository.requestPrepared("same", "operation", "payload",
                { error("成功重放不应再次准备") }) { _, _ -> error("成功重放不应再次写入") }
            assertEquals(result, replay)
        }
    }

    @Test
    fun `new database uses Lessplain directory without migrating old data`() {
        val old = root.resolve(".idea/code-understanding/understanding.db")
        Files.createDirectories(old.parent)
        Files.writeString(old, "旧数据")
        UnderstandingRepository(root).use { repository ->
            assertEquals(root.resolve(".idea/lessplain/understanding.db"), repository.databasePath)
            assertTrue(Files.exists(repository.databasePath))
            assertTrue(Files.exists(repository.databasePath.parent.resolve("writer.lock")))
            assertTrue(repository.recentRecords().isEmpty())
        }
        assertEquals("旧数据", Files.readString(old))
    }

    @Test
    fun `create and revision retries never validate changed source again`() {
        UnderstandingRepository(root).use { repository ->
            var content = "fun submit() { save() }"
            var preparations = 0
            val core = UnderstandingCore(repository, prepareGraph = {
                preparations++
                validateGraphAnchors(it) { AnchorSourceSnapshot(content) { "submit" } }
            })
            val record = core.present(4, "create", "保存流程", graph, "root")
            val changed = graph.copy(nodes = graph.nodes.map { it.copy(label = "写入") })
            val revision = core.revise(4, "revise", record.question, changed, record.id, 1)
            content = "fun submit() { reject() }"
            assertEquals(record, core.present(4, "create", record.question, graph, "root"))
            assertEquals(revision, core.revise(4, "revise", record.question, changed, record.id, 1))
            assertEquals(2, preparations)
            assertFailsWith<UnderstandingInputException> { core.present(4, "create", record.question, changed, "root") }
            assertEquals(2, preparations)
        }
    }

    @Test
    fun `failed answer keeps note and revision while successful answer retries after note removal`() {
        UnderstandingRepository(root).use { repository ->
            var content = "fun submit() { save() }"
            val core = UnderstandingCore(repository, prepareGraph = {
                validateGraphAnchors(it) { AnchorSourceSnapshot(content) { "submit" } }
            })
            val parent = core.present(4, "parent", "流程", graph, "root")
            val context = core.captureContext(parent.id, "save")
            val note = core.prepareQuestion("为什么保存", context.token).question
            content = "fun submit() { reject() }"
            assertFailsWith<UnderstandingInputException> {
                core.present(4, "answer", note.text, graph, "child", questionId = note.id)
            }
            assertEquals("pending", repository.getQuestion(note.id).status)
            assertEquals(1, repository.recentRecords().size)
            assertFailsWith<UnderstandingInputException> { core.revise(4, "bad-revise", parent.question, graph, parent.id, 1) }
            assertEquals(1, repository.getRecord(parent.id).revision)
            content = "fun submit() { save() }"
            val answer = core.present(4, "answer", note.text, graph, "child", questionId = note.id)
            assertFalse(repository.questionsForRecord(parent.id).any { it.id == note.id && it.status == "pending" })
            content = "changed"
            assertEquals(answer, core.present(4, "answer", note.text, graph, "child", questionId = note.id))
            assertEquals(2, repository.recentRecords().size)
        }
    }
}
