package com.lessplain.storage

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 删除、重试与来源读取交错时，回执和来源必须仍服从同一事务边界。 */
class RequestLifetimeTest {
    @TempDir lateinit var root: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())

    @Test fun `preparation failure replays a concurrently committed response`() {
        UnderstandingRepository(root).use { repository ->
            val preparing = CountDownLatch(1)
            val committed = CountDownLatch(1)
            Executors.newSingleThreadExecutor().use { pool ->
                val first = pool.submit<String> {
                    repository.requestPrepared("same", "create", "payload", {
                        preparing.countDown()
                        check(committed.await(5, TimeUnit.SECONDS))
                        error("question consumed by the successful retry")
                    }) { _, _ -> error("must replay") }
                }
                assertTrue(preparing.await(5, TimeUnit.SECONDS))
                repository.request("same", "create", "payload") { "saved" }
                committed.countDown()
                assertEquals("saved", first.get(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `deleted origin rejects late contexts and questions even when a snapshot is retained elsewhere`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "起始问题", graph, "root")
            val context = core.captureContext(record.id, "n")
            // 独立根图仍引用原快照，不能因此允许向已删除的来源追加问题。
            core.present(4, "other", "另一张图", graph, "root", context.token)
            repository.deleteRootRecord(record.id)
            assertEquals(context, repository.getContext(context.token))
            assertFailsWith<UnderstandingInputException> { repository.putContext(context.copy(token = "late")) }
            assertFailsWith<UnderstandingInputException> { core.prepareQuestion("迟到的问题", context.token) }
            assertTrue(repository.recentQuestions().isEmpty())
        }
    }

    @Test fun `question owner must match frozen source and answer retries never reactivate old answers`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "问题", graph, "root")
            val context = core.captureContext(record.id, "n")
            val question = core.prepareQuestion("为什么", context.token).question
            assertFailsWith<UnderstandingInputException> {
                repository.saveQuestion(question.copy(id = "wrong", ownerRecordId = null))
            }
            val first = repository.answerMaterialQuestion(question.id, "最初答复", null)
            val latest = repository.answerMaterialQuestion(question.id, "修正后的答复", null)
            assertEquals(first, repository.answerMaterialQuestion(question.id, "最初答复", null))
            assertEquals(listOf(latest), repository.answersForQuestion(question.id))
            repository.deleteRootRecord(record.id)
            assertFailsWith<UnderstandingInputException> { repository.answerMaterialQuestion(question.id, "最初答复", null) }
        }
    }
}
