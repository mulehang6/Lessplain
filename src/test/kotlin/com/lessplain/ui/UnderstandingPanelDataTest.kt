package com.lessplain.ui

import com.lessplain.core.UnderstandingCore
import com.lessplain.ide.PanelPage
import com.lessplain.model.BrowseState
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnderstandingPanelDataTest {
    @Test fun `history pagination includes roots beyond five hundred without duplicate boundaries`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())
            repeat(501) { core.present(4, "root-$it", "问题 $it", graph, "root") }
            val pages = (0..5).map { page -> readUnderstandingPanelData(repository,
                UnderstandingPanelRequest(PanelPage.HISTORY, null, null, emptyList(), "", page)) }
            assertEquals(List(5) { 100 } + 1, pages.map { it.history.size })
            assertEquals(501, pages.flatMap { it.history }.map { it.id }.toSet().size)
            assertEquals(List(5) { true } + false, pages.map { it.historyHasNext })
        }
    }

    @TempDir lateinit var directory: Path

    @Test fun `graph snapshot excludes history and contains correct root hierarchy and follow up owner`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())
            val root = core.present(4, "root", "根理解", graph, "root")
            core.present(4, "other", "无关理解", graph, "root")
            val question = core.prepareQuestion("追问", core.captureContext(root.id, "n").token).question
            val child = core.present(4, "child", question.text, graph, "child", questionId = question.id)
            val layer = FollowUpLayer(root.id, "n")
            val data = readUnderstandingPanelData(repository,
                UnderstandingPanelRequest(PanelPage.CANVAS, child.id, root.id, listOf(layer), ""))
            assertEquals(child, data.selected)
            assertEquals(root, data.displayed)
            assertEquals(root.id, data.hierarchyRootId)
            assertEquals(listOf(emptyList(), listOf(layer)), data.hierarchy.map { it.layers })
            assertTrue(data.history.isEmpty())
            assertTrue(data.recent.isEmpty())
        }
    }

    @Test fun `welcome and history load only their required lists`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())
            val root = core.present(4, "root", "标题", graph, "root")
            val browse = BrowseState(root.id, nodeId = "n", staticPath = listOf("n"), zoom = 1.5)
            repository.saveBrowseState(browse)
            val canvas = readUnderstandingPanelData(repository,
                UnderstandingPanelRequest(PanelPage.CANVAS, root.id, null, emptyList(), ""))
            assertEquals(browse, canvas.initialBrowse)
            val welcome = readUnderstandingPanelData(repository,
                UnderstandingPanelRequest(PanelPage.WELCOME, root.id, null, emptyList(), ""))
            assertEquals(listOf(root), welcome.recent)
            assertTrue(welcome.hierarchy.isEmpty())
            assertTrue(welcome.history.isEmpty())
            val history = readUnderstandingPanelData(repository,
                UnderstandingPanelRequest(PanelPage.HISTORY, root.id, null, emptyList(), "标题"))
            assertEquals(listOf(root), history.history)
            assertTrue(history.hierarchy.isEmpty())
            assertTrue(history.recent.isEmpty())
        }
    }
}
