package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.storage.PendingNoteItem
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.consumeReadFeedback
import com.lessplain.storage.editPendingQuestion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 直接覆盖当前消费事务的校验和来源保留规则，不再测试已撤下的读取协议。 */
class UnderstandingFeedbackConsumptionTest {
    @TempDir lateinit var root: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())

    @Test fun `changed feedback rolls back every deletion in the transaction`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "主图", graph, "root")
            val frozen = core.captureContext(record.id, "n")
            val first = core.prepareQuestion("第一条反馈", frozen.token).question
            val second = core.prepareQuestion("第二条反馈", frozen.token).question
            val items = listOf(first, second).map { PendingNoteItem(it.id, it.text, frozen) }
            repository.editPendingQuestion(second.id, "读取后补充的反馈")
            assertFailsWith<UnderstandingInputException> { repository.write { db -> repository.consumeReadFeedback(db, items) } }
            assertEquals(first, repository.getQuestion(first.id))
            assertEquals("读取后补充的反馈", repository.getQuestion(second.id).text)
            assertEquals(frozen, repository.getContext(frozen.token))
        }
    }

    @Test fun `consumption retains contexts used by saved graphs and other questions`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "主图", graph, "root")
            val saved = core.captureContext(record.id, "n")
            core.present(4, "child", "子图", graph, "child", saved.token)
            val shared = core.captureContext(record.id, "n")
            val remaining = core.prepareQuestion("保留的问题", shared.token).question
            val unused = core.captureContext(record.id, "n")
            val items = listOf(saved, shared, unused).map { context ->
                val question = core.prepareQuestion("缺陷反馈", context.token).question
                PendingNoteItem(question.id, question.text, context)
            }
            repository.write { db -> repository.consumeReadFeedback(db, items) }
            assertEquals(saved, repository.getContext(saved.token))
            assertEquals(shared, repository.getContext(shared.token))
            assertEquals(remaining, repository.getQuestion(remaining.id))
            assertFailsWith<UnderstandingInputException> { repository.getContext(unused.token) }
            items.forEach { assertFailsWith<UnderstandingInputException> { repository.getQuestion(it.questionId) } }
        }
    }
}
