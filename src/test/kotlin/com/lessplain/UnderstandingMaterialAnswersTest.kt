package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.answerMaterialQuestion
import com.lessplain.storage.cancelPendingQuestion
import com.lessplain.storage.editPendingQuestion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 使用真实 SQLite 并发连接验证回答的事务边界，不依赖 UI 时序。 */
class UnderstandingMaterialAnswersTest {
    @TempDir lateinit var root: Path

    @Test fun `cancelled question is never revived and revisions supersede only current answer`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "answers-owner", "来源图", UnderstandingGraph(listOf(GraphNode("node", "节点")), emptyList()), "root")
            val context = core.captureContext(record.id, "node")
            val cancelled = core.prepareQuestion("已取消", context.token).question
            repository.cancelPendingQuestion(cancelled.id)
            assertFailsWith<UnderstandingInputException> { repository.answerMaterialQuestion(cancelled.id, "回答", null) }
            assertEquals("cancelled", repository.getQuestion(cancelled.id).status)
            assertTrue(repository.answersForQuestion(cancelled.id).isEmpty())
            val pending = core.prepareQuestion("原问题", context.token).question
            repository.editPendingQuestion(pending.id, "修改后的问题")
            val first = repository.answerMaterialQuestion(pending.id, "初次回答", null)
            assertEquals(first, repository.answerMaterialQuestion(pending.id, "初次回答", null))
            val second = repository.answerMaterialQuestion(pending.id, "修订回答", null)
            assertEquals(first.id, second.supersedesId)
            assertEquals(listOf(second), repository.answersForQuestion(pending.id))
            assertEquals("修改后的问题", repository.getQuestion(pending.id).text)
            assertEquals("answered", repository.answersForRecord(record.id).single().first.status)
        }
    }

    @Test fun `concurrent identical answers create one durable answer`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val question = core.prepareQuestion("同一个问题", core.captureContext().token).question
            val gate = CountDownLatch(1)
            Executors.newFixedThreadPool(8).use { pool ->
                val replies = (1..8).map { pool.submit<String> {
                    check(gate.await(10, TimeUnit.SECONDS))
                    repository.answerMaterialQuestion(question.id, "同一个回答", null).id
                } }
                gate.countDown()
                assertEquals(1, replies.map { it.get(10, TimeUnit.SECONDS) }.distinct().size)
            }
            repository.connect().use { db -> db.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM answers").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
            } }
            assertEquals("answered", repository.getQuestion(question.id).status)
        }
    }

    @Test fun `cancel and edit racing an answer have only linearizable outcomes`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val context = core.captureContext()
            Executors.newFixedThreadPool(2).use { pool ->
                repeat(10) { index ->
                    val pending = core.prepareQuestion("问题$index", context.token).question
                    val gate = CountDownLatch(1)
                    val cancellation = pool.submit<Boolean> {
                        check(gate.await(10, TimeUnit.SECONDS))
                        runCatching { repository.cancelPendingQuestion(pending.id) }.isSuccess
                    }
                    val answer = pool.submit<Boolean> {
                        check(gate.await(10, TimeUnit.SECONDS))
                        runCatching { repository.answerMaterialQuestion(pending.id, "回答", null) }.isSuccess
                    }
                    gate.countDown()
                    val cancelled = cancellation.get(10, TimeUnit.SECONDS)
                    assertNotEquals(cancelled, answer.get(10, TimeUnit.SECONDS))
                    assertEquals(if (cancelled) "cancelled" else "answered", repository.getQuestion(pending.id).status)
                    assertEquals(if (cancelled) 0 else 1, repository.answersForQuestion(pending.id).size)

                    val edited = core.prepareQuestion("编辑前", context.token).question
                    val editGate = CountDownLatch(1)
                    val edit = pool.submit<Boolean> {
                        check(editGate.await(10, TimeUnit.SECONDS))
                        runCatching { repository.editPendingQuestion(edited.id, "编辑后") }.isSuccess
                    }
                    val replying = pool.submit { check(editGate.await(10, TimeUnit.SECONDS)); repository.answerMaterialQuestion(edited.id, "答复", null) }
                    editGate.countDown()
                    val didEdit = edit.get(10, TimeUnit.SECONDS)
                    replying.get(10, TimeUnit.SECONDS)
                    assertEquals(if (didEdit) "编辑后" else "编辑前", repository.getQuestion(edited.id).text)
                    assertEquals("answered", repository.getQuestion(edited.id).status)
                }
            }
        }
    }
}
