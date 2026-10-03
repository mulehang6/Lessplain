package com.master.understanding.diagram

import com.master.understanding.core.StepDirection
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import org.junit.jupiter.api.Test
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import kotlin.test.assertEquals

class RouteChoicePopupTest {
    private val edges = listOf(
        GraphEdge("first", "a", "d", "control_flow", "首次"),
        GraphEdge("retry", "a", "d", "control_flow", "重试"),
    )
    private val graph = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("d", "D")), edges)

    @Test
    fun `hiding before an item action still delivers that precise edge instead of cancellation`() {
        val results = mutableListOf<String?>()
        SwingUtilities.invokeAndWait {
            val popup = RouteChoicePopup(graph, StepDirection.NEXT, edges, results::add)
            val event = PopupMenuEvent(popup.menu)
            popup.menu.popupMenuListeners.forEach { it.popupMenuWillBecomeInvisible(event) }
            (popup.menu.getComponent(1) as JMenuItem).doClick(0)
            assertEquals(listOf<String?>("retry"), results)
            popup.close()
        }
        SwingUtilities.invokeAndWait { assertEquals(listOf<String?>("retry"), results) }
    }

    @Test
    fun `escape cancels once and ignores a subsequent item action`() {
        val results = mutableListOf<String?>()
        SwingUtilities.invokeAndWait {
            val popup = RouteChoicePopup(graph, StepDirection.PREVIOUS, edges, results::add)
            val event = PopupMenuEvent(popup.menu)
            popup.menu.popupMenuListeners.forEach { it.popupMenuCanceled(event) }
            popup.menu.popupMenuListeners.forEach { it.popupMenuWillBecomeInvisible(event) }
            (popup.menu.getComponent(0) as JMenuItem).doClick(0)
            assertEquals(listOf<String?>(null), results)
            popup.close()
        }
        SwingUtilities.invokeAndWait { assertEquals(listOf<String?>(null), results) }
    }

    @Test
    fun `closing a pending popup discards its deferred cancellation callback`() {
        val results = mutableListOf<String?>()
        SwingUtilities.invokeAndWait {
            val popup = RouteChoicePopup(graph, StepDirection.NEXT, edges, results::add)
            popup.menu.popupMenuListeners.forEach { it.popupMenuWillBecomeInvisible(PopupMenuEvent(popup.menu)) }
            popup.close()
        }
        SwingUtilities.invokeAndWait { assertEquals(emptyList(), results) }
    }
}
