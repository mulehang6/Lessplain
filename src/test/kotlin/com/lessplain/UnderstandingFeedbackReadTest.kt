package com.lessplain

import com.google.gson.JsonParser
import com.lessplain.core.UnderstandingCore
import com.lessplain.mcp.parseFeedbackQuestionIds
import com.lessplain.mcp.readUnderstandingContextResponse
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.pendingNoteBatch
import com.lessplain.storage.editPendingQuestion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 缺陷读取与源码回答是两条消费流程，反馈读取无需提交新图。 */
class UnderstandingFeedbackReadTest {
    @TempDir lateinit var root: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())

    @Test fun `feedback read removes only requested notes without any history or child graph`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "主图", graph, "root")
            val feedback = core.prepareQuestion("文字被截断", core.captureContext(record.id, "n").token).question
            val question = core.prepareQuestion("为什么需要重试？", core.captureContext(record.id, "n").token).question
            val response = readUnderstandingContextResponse(repository, core.captureContext(record.id), setOf(feedback.id))
            val wire = JsonParser.parseString(response).asJsonObject
            assertTrue(wire["pendingBatch"].asJsonObject["items"].asJsonArray.any {
                it.asJsonObject["questionId"].asString == feedback.id && it.asJsonObject["question"].asString == feedback.text
            })
            assertEquals(listOf(question.id), repository.pendingNoteBatch(record.id).items.map { it.questionId })
            assertFailsWith<UnderstandingInputException> { repository.getQuestion(feedback.id) }
            assertFailsWith<UnderstandingInputException> { repository.getContext(feedback.contextToken) }
            DriverManager.getConnection("jdbc:sqlite:${repository.databasePath}").use { db ->
                db.createStatement().use { statement ->
                    listOf("records" to 1, "answers" to 0, "questions" to 1, "requests" to 1).forEach { (table, count) ->
                        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                            rows.next()
                            assertEquals(count, rows.getInt(1), table)
                        }
                    }
                }
            }
            assertEquals(listOf(question.id), JsonParser.parseString(
                readUnderstandingContextResponse(repository, core.captureContext(record.id)),
            ).asJsonObject["pendingBatch"].asJsonObject["items"].asJsonArray.map { it.asJsonObject["questionId"].asString })
        }
    }

    @Test fun `serialization or wrong scope failure keeps feedback`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "主图", graph, "root")
            val other = core.present(4, "other", "另一张图", graph, "root")
            val feedback = core.prepareQuestion("路由错误", core.captureContext(record.id, "n").token).question
            assertFailsWith<IllegalStateException> {
                readUnderstandingContextResponse(repository, core.captureContext(record.id), setOf(feedback.id)) { error("模拟序列化失败") }
            }
            assertFailsWith<UnderstandingInputException> {
                readUnderstandingContextResponse(repository, core.captureContext(other.id), setOf(feedback.id))
            }
            assertEquals(listOf(feedback.id), repository.pendingNoteBatch(record.id).items.map { it.questionId })
        }
    }

    @Test fun `editing one feedback during reading rolls back the whole cleanup`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "root", "主图", graph, "root")
            val first = core.prepareQuestion("第一条反馈", core.captureContext(record.id, "n").token).question
            val second = core.prepareQuestion("第二条反馈", core.captureContext(record.id, "n").token).question
            assertFailsWith<UnderstandingInputException> {
                readUnderstandingContextResponse(repository, core.captureContext(record.id), setOf(first.id, second.id)) {
                    repository.editPendingQuestion(second.id, "读取期间补充的反馈")
                    com.google.gson.Gson().toJson(it)
                }
            }
            val notes = repository.pendingNoteBatch(record.id).items
            assertEquals(listOf(first.id, second.id), notes.map { it.questionId })
            assertEquals("读取期间补充的反馈", notes.last().question)
            assertEquals(first.contextToken, repository.getContext(first.contextToken).token)
        }
    }

    @Test fun `question IDs must be an explicit string array`() {
        assertEquals(setOf("a", "b"), parseFeedbackQuestionIds("[\"a\",\"b\",\"a\"]"))
        assertFailsWith<UnderstandingInputException> { parseFeedbackQuestionIds("{}") }
        assertFailsWith<UnderstandingInputException> { parseFeedbackQuestionIds("[1]") }
    }
}
