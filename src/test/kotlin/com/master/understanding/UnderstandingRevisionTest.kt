package com.master.understanding

import com.master.understanding.core.UnderstandingCore
import com.master.understanding.ide.LocationStatus
import com.master.understanding.ide.matchAnchorText
import com.master.understanding.model.BrowseState
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.SourceAnchor
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.storage.UnderstandingRepository
import com.master.understanding.storage.sha256
import com.master.understanding.storage.updateDisplayTitle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnderstandingRevisionTest {
    @TempDir lateinit var root: Path

    @Test
    fun `revision preserves identity questions and browse state`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val original = core.present(3, "create", "如何登录？", graph("if (ok)"), "root")
            val titled = repository.updateDisplayTitle(original.id, "登录全链路")
            val context = core.captureContext(titled.id, "decision")
            val question = core.prepareQuestion("为什么检查？", context.token).question
            val browse = BrowseState(titled.id, nodeId = "decision", staticPath = listOf("pass"), zoom = 1.4,
                panX = 12.0, panY = 24.0, hasPan = true)
            repository.saveBrowseState(browse)

            val revisedGraph = graph("if (ok) {\n    login()\n}")
            val revised = core.revise(3, "revise", titled.question, revisedGraph, titled.id, titled.revision)

            assertEquals(titled.id, revised.id)
            assertEquals(titled.revision + 1, revised.revision)
            assertEquals(titled.displayTitle, revised.displayTitle)
            assertEquals(titled.createdAt, revised.createdAt)
            assertEquals(revisedGraph, repository.getRecord(titled.id).graph)
            assertEquals(question.id, repository.questionsForRecord(titled.id).single().id)
            assertEquals(browse, repository.getBrowseState(titled.id))
            assertEquals(revised, core.revise(3, "revise", titled.question, revisedGraph, titled.id, titled.revision))
            assertFailsWith<UnderstandingInputException> {
                core.revise(3, "revise", titled.question, graph("if (ok) { login() }"), titled.id, titled.revision)
            }

            assertFailsWith<UnderstandingInputException> {
                core.revise(3, "stale", titled.question, graph("if (ok) { login() }"), titled.id, titled.revision)
            }
            assertFailsWith<UnderstandingInputException> {
                core.revise(3, "changed-node", titled.question,
                    revisedGraph.copy(nodes = revisedGraph.nodes.dropLast(1) + GraphNode("other", "另一步"),
                        edges = listOf(GraphEdge("pass", "decision", "other", "control_flow"))),
                    titled.id, revised.revision)
            }
            assertEquals(revised.revision, repository.getRecord(titled.id).revision)
        }
    }

    @Test
    fun `multiline revised anchor resolves from its first line`() {
        val content = "class Login {\n    fun check() {\n        if (ok) {\n            login()\n        }\n    }\n}\n"
        val block = "if (ok) {\n            login()\n        }"
        val anchor = SourceAnchor("src/Login.kt", "Login.check", block, sha256(content), 3)
        val located = matchAnchorText(anchor, content) { "Login.check" }
        assertEquals(LocationStatus.EXACT, located.location)
        assertEquals(content.indexOf(block), located.offset)
    }

    private fun graph(anchorText: String) = UnderstandingGraph(
        nodes = listOf(
            GraphNode("decision", "检查", anchor = SourceAnchor("src/Login.kt", "Login.check", anchorText, "hash", 1)),
            GraphNode("done", "完成"),
        ),
        edges = listOf(GraphEdge("pass", "decision", "done", "control_flow")),
    )
}
