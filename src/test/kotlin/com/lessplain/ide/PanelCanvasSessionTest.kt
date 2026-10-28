package com.lessplain.ide

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.ui.FollowUpLayer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class PanelCanvasSessionTest {
    @TempDir lateinit var root: Path
    @Test fun `route survives recreation history navigation and unrelated deletion`() {
        var saved: String? = null
        val store = PanelCanvasSession({ saved }, { saved = it })
        val route = PanelCanvasRoute("root", listOf(FollowUpLayer("root", "node"), FollowUpLayer("answer", "next")))
        store.remember(route)
        assertEquals(route, PanelCanvasSession({ saved }, { saved = it }).load())
        store.removeDeleted(setOf("unrelated")); assertEquals(route, store.load())
        store.removeDeleted(setOf("answer")); assertNull(store.load())
        saved = "invalid-json"; assertNull(store.load())
    }
    @Test fun `restore validates root layer ancestry and exact node identity`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())
            val record = core.present(4, "root", "问题", graph, "root")
            val question = core.prepareQuestion("追问", core.captureContext(record.id, "n").token).question
            val child = core.present(4, "child", question.text, graph, "child", questionId = question.id)
            val route = PanelCanvasRoute(record.id, listOf(FollowUpLayer(record.id, "n"), FollowUpLayer(child.id, "n")))
            assertEquals(listOf(record, record, child), loadPanelCanvas(repository, route))
            assertFails { loadPanelCanvas(repository, route.copy(layers = listOf(FollowUpLayer(record.id, "missing")))) }
            assertFails { loadPanelCanvas(repository, PanelCanvasRoute(child.id)) }
            assertFails { loadPanelCanvas(repository, PanelCanvasRoute("missing")) }
        }
    }
}
