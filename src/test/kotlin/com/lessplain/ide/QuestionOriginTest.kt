package com.lessplain.ide

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class QuestionOriginTest {
    @TempDir lateinit var root: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("a", "节点 A"), GraphNode("b", "节点 B")), emptyList())

    @Test fun `exact question targets restore their frozen excerpts independently of current source`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val firstSource = SourceAnchor("Origin.kt", null, "原始中文代码😀", "snapshot", 1)
            val initial = core.captureContext(anchor = firstSource)
            val record = core.present(4, "root", "起始问题全文", graph, "root", contextToken = initial.token)
            fun answer(node: String, text: String, source: SourceAnchor?) = core.prepareQuestion(text,
                core.captureContext(record.id, node, source).token).question.let { question ->
                core.present(4, "answer-$text", text, graph, "child", questionId = question.id)
                question
            }
            val q1 = answer("a", "为什么这样写？", firstSource)
            val changedSource = firstSource.copy(text = "另一处同名代码")
            val q2 = answer("a", "第二个问题", changedSource)
            val qb = answer("b", "其他层的问题", null)
            core.prepareQuestion("尚未提交给 Agent 的回答", core.captureContext(record.id, "a", firstSource).token)
            Files.writeString(root.resolve("Origin.kt"), "源码现在已经改变")
            core.revise(4, "revise", record.question, graph.copy(nodes = graph.nodes.map { it.copy(label = "改名") }), record.id, 1)
            val pages = listOf(q1, q2).map { readQuestionOrigin(repository, QuestionOriginTarget(requireNotNull(repository.recordForQuestion(it.id)).id, it.id)) }
            assertEquals(setOf(q1.id, q2.id), pages.map { it.questionId }.toSet())
            assertEquals(firstSource, pages.first { it.questionId == q1.id }.anchor)
            assertEquals(changedSource, pages.first { it.questionId == q2.id }.anchor)
            assertEquals("为什么这样写？", pages.first { it.questionId == q1.id }.question)
            val rootPage = readQuestionOrigin(repository, QuestionOriginTarget(record.id))
            assertEquals("起始问题全文", rootPage.question)
            assertEquals(firstSource, rootPage.anchor)
            val other = readQuestionOrigin(repository, QuestionOriginTarget(requireNotNull(repository.recordForQuestion(qb.id)).id, qb.id))
            assertEquals("其他层的问题", other.question)
            assertNull(other.anchor)
            assertFails { readQuestionOrigin(repository, QuestionOriginTarget("missing")) }
            assertFails { readQuestionOrigin(repository, QuestionOriginTarget(requireNotNull(repository.recordForQuestion(q1.id)).id, q2.id)) }
        }
    }
}
