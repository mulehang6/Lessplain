package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteChoicePopupTest {
    @Test
    fun `deferred dismissal cannot override a precise selection`() {
        val results = mutableListOf<String?>()
        SwingUtilities.invokeAndWait {
            val selection = RouteChoiceSelection(results::add)
            selection.cancelLater()
            selection.choose("retry")
            selection.choose("first")
        }
        SwingUtilities.invokeAndWait { assertEquals(listOf<String?>("retry"), results) }
    }

    @Test
    fun `cancellation is once only and programmatic close discards queued cancellation`() {
        val results = mutableListOf<String?>()
        SwingUtilities.invokeAndWait {
            val selection = RouteChoiceSelection(results::add)
            selection.choose(null)
            selection.cancelLater()
            selection.choose("first")
            val closed = RouteChoiceSelection(results::add)
            closed.cancelLater()
            closed.close()
            closed.choose("retry")
        }
        SwingUtilities.invokeAndWait { assertEquals(listOf<String?>(null), results) }
    }

    @Test
    fun `arbitrary candidate count and conditions retain exact edge ids`() {
        val edges = (1..25).map { GraphEdge("edge-$it", "a", "b", "call", "条件$it") }
        val graph = UnderstandingGraph(listOf(GraphNode("a", "来源"), GraphNode("b", "目标")), edges)
        val items = routeChoiceItems(graph, edges)
        assertEquals(edges.map { it.id }, items.map { it.edgeId })
        assertEquals(25, items.size)
        assertTrue(items.last().label.contains("条件25"))
    }

    @Test
    fun `popup stays inside a scrolled editor viewport including oversize lists`() {
        val visible = Rectangle(150, 400, 320, 180)
        val beside = routeChoicePopupBounds(Point(300, 430), visible, Dimension(120, 80))
        assertEquals(Rectangle(300, 430, 120, 80), beside)
        val nearEdge = routeChoicePopupBounds(Point(465, 575), visible, Dimension(200, 100))
        assertEquals(Rectangle(270, 480, 200, 100), nearEdge)
        val oversized = routeChoicePopupBounds(Point(465, 575), visible, Dimension(900, 600))
        assertEquals(visible, oversized)
    }
}
