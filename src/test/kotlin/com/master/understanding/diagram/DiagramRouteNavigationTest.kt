package com.master.understanding.diagram

import com.master.understanding.core.StaticExplorer
import com.master.understanding.model.BrowseState
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramRouteNavigationTest {
    private val graph = UnderstandingGraph(
        listOf("email", "wx", "merge", "save").map { GraphNode(it, it) },
        listOf(GraphEdge("email-merge", "email", "merge", "call"), GraphEdge("wx-merge", "wx", "merge", "call"),
            GraphEdge("merge-save", "merge", "save", "control_flow")),
    )

    private fun withCanvas(g: UnderstandingGraph = graph,
        action: (UnderstandingDiagram, StaticExplorer, DiagramRouteNavigation, MutableList<String>, MutableList<JPopupMenu>) -> Unit,
    ) = SwingUtilities.invokeAndWait {
        val layout = layoutUnderstandingGraph(g)
        val record = UnderstandingRecord("test", 1, null, null, "导航", "", null, g, 0)
        val diagram = UnderstandingDiagram(record, null, layoutComputer = { layout })
        val explorer = StaticExplorer(g)
        val commits = mutableListOf<String>()
        val menus = mutableListOf<JPopupMenu>()
        diagram.layout = layout
        diagram.setSize(900, 650)
        val navigation = DiagramRouteNavigation(diagram, explorer, { commits += explorer.currentNodeId }, { menus += it })
        try { action(diagram, explorer, navigation, commits, menus) } finally { navigation.close(); diagram.dispose() }
    }

    @Test
    fun `direct click unfolds upstream routes and explicit completion selects an exact edge`() = withCanvas { diagram, route, nav, commits, menus ->
        nav.select("save")
        assertEquals(listOf("save"), commits)
        assertTrue(route.path.isEmpty())
        assertTrue(diagram.routeEdgeIds.isEmpty(), "unresolved upstream routes must remain static")
        assertEquals(setOf("email-merge", "wx-merge", "merge-save"), diagram.completeRouteNavigation.upstreamEdgeIds)
        assertEquals(emptySet(), diagram.choiceTargets)
        assertTrue(diagram.controls.routeButton.isVisible)
        diagram.controls.routeButton.doClick()
        assertEquals(setOf("email", "wx"), diagram.choiceTargets)
        assertFalse(diagram.controls.previousButton.isEnabled)
        assertFalse(diagram.controls.nextButton.isEnabled)
        assertEquals(listOf("save"), commits, "preview must not navigate source")
        menus.last().components.filterIsInstance<JMenuItem>().last().doClick(0)
        assertEquals("save", diagram.currentNodeId)
        assertEquals(listOf("wx-merge", "merge-save"), route.path)
        assertEquals(listOf("save", "save"), commits)
        assertFalse(diagram.controls.routeButton.isVisible)
    }

    @Test
    fun `cancel restores activation viewport and preserves direct selection`() = withCanvas { diagram, route, nav, commits, _ ->
        nav.select("save")
        diagram.zoom = 1.7
        diagram.centerX = 64.0
        diagram.centerY = 95.0
        nav.complete()
        nav.cancel()
        assertEquals(1.7, diagram.zoom)
        assertEquals(Point(64, 95), Point(diagram.centerX.toInt(), diagram.centerY.toInt()))
        assertEquals("save", diagram.currentNodeId)
        assertTrue(route.path.isEmpty())
        assertEquals(setOf("email-merge", "wx-merge", "merge-save"), diagram.completeRouteNavigation.upstreamEdgeIds)
        assertEquals(listOf("save"), commits)
        assertEquals(emptySet(), diagram.choiceTargets)
    }

    @Test
    fun `blank click cancels but dragging background does not`() = withCanvas { diagram, _, nav, _, _ ->
        nav.select("save")
        nav.complete()
        val mouse = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
        fun event(kind: Int, point: Point) = MouseEvent(diagram, kind, 0, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1)
        val blank = Point(850, 620)
        mouse.mousePressed(event(MouseEvent.MOUSE_PRESSED, blank))
        mouse.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, Point(870, 630)))
        mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, Point(870, 630)))
        assertTrue(nav.hasChoice)
        mouse.mousePressed(event(MouseEvent.MOUSE_PRESSED, blank))
        mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, blank))
        assertFalse(nav.hasChoice)
    }

    @Test
    fun `keyboard chooses candidates without moving target and escape cancels`() = withCanvas { diagram, route, nav, commits, _ ->
        nav.select("save")
        nav.complete()
        fun press(action: String) = diagram.actionMap.get(action).actionPerformed(ActionEvent(diagram, 0, action))
        press("next-node")
        assertEquals("save", diagram.currentNodeId)
        assertEquals(listOf("save"), commits)
        press("open-questions")
        assertEquals(listOf("wx-merge", "merge-save"), route.path)
        nav.complete("save")
        press("cancel-route")
        assertFalse(nav.hasChoice)
        assertEquals(listOf("wx-merge", "merge-save"), route.path)
    }

    @Test
    fun `same target parallel edges open conditions and stale menu cannot commit`() {
        val parallel = UnderstandingGraph(listOf(GraphNode("a", "支付检查"), GraphNode("b", "返回")),
            listOf(GraphEdge("first", "a", "b", "control_flow", "首次支付"),
                GraphEdge("repeat", "a", "b", "control_flow", "重复回调")))
        withCanvas(parallel) { diagram, route, nav, commits, menus ->
            nav.select("a")
            diagram.nextStep()
            assertEquals(1, menus.size)
            val repeat = menus.last().components.filterIsInstance<JMenuItem>().last()
            assertTrue(repeat.text.contains("重复回调"))
            assertEquals("a", route.currentNodeId)
            repeat.doClick()
            assertEquals(listOf("repeat"), route.path)
            nav.complete("b")
            val stale = menus.last().components.filterIsInstance<JMenuItem>().first()
            val saved = commits.toList()
            nav.cancel()
            stale.doClick()
            assertEquals(saved, commits)
            assertEquals(listOf("repeat"), route.path)
            nav.complete("b")
            val disposed = menus.last().components.filterIsInstance<JMenuItem>().first()
            nav.close()
            disposed.doClick()
            assertEquals(saved, commits)
            assertNull(diagram.routeNavigation)
            assertFalse(diagram.controls.routeButton.isVisible)
        }
    }

    @Test
    fun `external snapshot changes cancel pending choices while equal snapshot preserves them`() = withCanvas { diagram, route, nav, _, menus ->
        nav.select("save")
        nav.complete()
        nav.synchronize(route.path, route.currentNodeId)
        assertTrue(nav.hasChoice)
        val saved = BrowseState(diagram.record.id, route.currentNodeId, route.path, navigationState = route.navigationState)
        assertFalse(nav.synchronize(route.currentNodeId, saved))
        assertTrue(nav.hasChoice)
        menus.last().components.filterIsInstance<JMenuItem>().last().doClick(0)
        assertEquals(listOf("wx-merge", "merge-save"), route.path)
        nav.select("save")
        nav.complete("save")
        assertTrue(nav.hasChoice)
        val stale = menus.last().components.filterIsInstance<JMenuItem>().last()
        val invalid = saved.copy(navigationState = saved.navigationState?.copy(prefixNodeId = "missing"))
        assertFalse(nav.synchronize("save", invalid))
        assertFalse(nav.hasChoice)
        stale.doClick(0)
        assertTrue(route.path.isEmpty())
        nav.complete("save")
        assertTrue(nav.hasChoice)
        nav.synchronize(emptyList(), "email")
        assertFalse(nav.hasChoice)
        assertEquals("email", diagram.currentNodeId)
        assertEquals(emptySet(), diagram.choiceTargets)
    }

    @Test
    fun `revision invalidation prevents same generation menu from submitting`() = SwingUtilities.invokeAndWait {
        val g = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")),
            listOf(GraphEdge("first", "a", "b", "control_flow", "首次"), GraphEdge("retry", "a", "b", "control_flow", "重试")))
        val layout = layoutUnderstandingGraph(g)
        val record = UnderstandingRecord("test", 1, null, null, "流程", "", null, g, 0)
        val diagram = UnderstandingDiagram(record, null, layoutComputer = { layout })
        var valid = true
        var commits = 0
        var menu: JPopupMenu? = null
        diagram.layout = layout
        val nav = DiagramRouteNavigation(diagram, StaticExplorer(g), { commits++ }, { menu = it }, { valid })
        try {
            nav.select("b")
            nav.complete()
            val stale = requireNotNull(menu).components.filterIsInstance<JMenuItem>().first()
            valid = false
            stale.doClick()
            assertEquals(1, commits)
            assertTrue(nav.isClosed)
            assertNull(diagram.routeNavigation)
        } finally { nav.close(); diagram.dispose() }
    }
}
