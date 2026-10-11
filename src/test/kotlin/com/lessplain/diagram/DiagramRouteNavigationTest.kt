package com.lessplain.diagram

import com.lessplain.core.StaticExplorer
import com.lessplain.ide.CanvasNavigationSessions
import com.lessplain.model.BrowseState
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramRouteNavigationTest {
    private val graph = UnderstandingGraph(
        listOf("email", "wx", "merge", "save").map { GraphNode(it, it) },
        listOf(
            GraphEdge("email-merge", "email", "merge", "call"), GraphEdge("wx-merge", "wx", "merge", "call"),
            GraphEdge("merge-save", "merge", "save", "control_flow"),
        ),
    )

    private fun withCanvas(
        g: UnderstandingGraph = graph,
        action: (
            UnderstandingDiagram, StaticExplorer, DiagramRouteNavigation, MutableList<String>
        ) -> Unit,
    ) = SwingUtilities.invokeAndWait {
        val layout = layoutUnderstandingGraph(g)
        val record = UnderstandingRecord("test", 1, null, null, "导航", "", null, g, 0)
        val diagram = UnderstandingDiagram(record, null, layoutComputer = { layout })
        val explorer = StaticExplorer(g)
        val commits = mutableListOf<String>()
        diagram.layout = layout
        diagram.setSize(900, 650)
        val navigation = DiagramRouteNavigation(
            diagram, explorer,
            { commits += explorer.currentNodeId },
        )
        try {
            action(diagram, explorer, navigation, commits)
        } finally {
            navigation.close(); diagram.dispose()
        }
    }

    @Test
    fun `graph completion highlights only and candidate node confirms a precise route`() =
        withCanvas { diagram, route, nav, commits ->
            nav.select("save")
            assertEquals(setOf("email-merge", "wx-merge", "merge-save"), diagram.pathHighlights.upstreamEdgeIds)
            assertTrue(route.path.isEmpty())
            assertEquals("补全来路", diagram.controls.routeButton.accessibleContext.accessibleName)
            assertTrue(diagram.controls.routeButton.toolTipText.contains("补全来路"))
            assertTrue(diagram.controls.routeButton.text.isNullOrEmpty())
            diagram.controls.arrangeSteps(80, 160)
            val buttons =
                listOf(diagram.controls.previousButton, diagram.controls.nextButton, diagram.controls.routeButton)
            assertTrue(buttons.all { it.x >= 0 && it.x + it.width <= 80 && it.width == 32 })
            assertTrue(buttons.zipWithNext().all { (first, next) -> !first.bounds.intersects(next.bounds) })
            val completionIcon = diagram.controls.routeButton.icon
            diagram.controls.routeButton.doClick()
            assertTrue(diagram.controls.routeButton.icon !== completionIcon)
            assertEquals("取消选路", diagram.controls.routeButton.accessibleContext.accessibleName)
            assertEquals(setOf("email", "wx"), diagram.choiceTargets)
            assertEquals(setOf("email-merge", "wx-merge"), diagram.routeChoice.edgeIds)
            assertTrue(diagram.controls.previousButton.isEnabled)
            assertFalse(diagram.controls.nextButton.isEnabled)
            diagram.selectUnderstandingNode("wx", notify = true)
            assertEquals("save", diagram.currentNodeId)
            assertEquals(listOf("wx-merge", "merge-save"), route.path)
            assertEquals(listOf("save", "save"), commits)
            assertTrue(diagram.choiceTargets.isEmpty())
            assertTrue(diagram.controls.routeButton.isVisible)
        }

    @Test
    fun `source entry reuses graph completion candidates and confirms in the graph`() =
        withCanvas { diagram, route, nav, commits ->
            nav.select("save")
            nav.complete()
            val snapshot = route.navigationState
            val sessions = CanvasNavigationSessions()
            val registration = sessions.register(
                matches = { r, n -> r == "test" && n == "save" }, busy = { nav.hasChoice },
                previous = diagram::previousStep, next = diagram::nextStep,
                sourceStep = nav::stepFromSource, cancel = nav::cancel,
            )
            try {
                assertTrue(sessions.step("test", "save", true))
                assertEquals(snapshot, route.navigationState)
                nav.previewCandidateNode("wx")
                assertEquals("wx", diagram.routeChoice.focusedNodeId)
                assertEquals(setOf("email", "wx"), diagram.choiceTargets)
                assertEquals(listOf("save"), commits)
                diagram.selectUnderstandingNode("wx", notify = true)
                assertEquals(listOf("wx-merge", "merge-save"), route.path)
                assertNull(diagram.routeChoice.focusedNodeId)
            } finally { registration.close() }
        }

    @Test
    fun `parallel candidates highlight edges and node click keeps the choice pending`() {
        withCanvas(parallelGraph()) { diagram, route, nav, commits ->
            diagram.nextStep()
            assertEquals(setOf("first", "repeat"), diagram.routeChoice.edgeIds)
            diagram.selectUnderstandingNode("b", notify = true)
            assertTrue(nav.hasChoice)
            assertEquals("a", route.currentNodeId)
            assertTrue(commits.isEmpty())
            val placed = diagram.layout.edges.first { it.edge.id == "repeat" }
            val label = requireNotNull(placed.resolvedLabelBounds)
            val point = screen(diagram, label.centerX, label.centerY)
            assertEquals("repeat", diagram.candidateEdgeAt(point))
            val mouse = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
            mouse.mouseMoved(event(MouseEvent.MOUSE_MOVED, point, diagram))
            assertEquals("repeat", diagram.routeChoice.focusedEdgeId)
            val press = event(MouseEvent.MOUSE_PRESSED, point, diagram)
            mouse.mousePressed(press)
            mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, point, diagram))
            assertEquals(listOf("repeat"), route.path)
            assertEquals(listOf("b"), commits)
            assertTrue(diagram.routeChoice.edgeIds.isEmpty())
        }
    }

    @Test
    fun `keyboard enumerates parallel edges and escape preserves committed route`() =
        withCanvas(parallelGraph()) { diagram, route, nav, commits ->
            diagram.nextStep()
            press(diagram, "next-node")
            assertEquals("repeat", diagram.routeChoice.focusedEdgeId)
            assertEquals("a", route.currentNodeId)
            press(diagram, "open-questions")
            assertEquals(listOf("repeat"), route.path)
            assertEquals(listOf("b"), commits)
            nav.complete("b")
            press(diagram, "cancel-route")
            assertFalse(nav.hasChoice)
            assertEquals(listOf("repeat"), route.path)
            assertTrue(diagram.routeChoice.edgeIds.isEmpty())
        }

    @Test
    fun `graph inspection and blank clicks retain choices and explicit cancel preserves the current viewport`() =
        withCanvas { diagram, route, nav, commits ->
            nav.select("save")
            diagram.zoom = 1.7
            diagram.centerX = 64.0
            diagram.centerY = 95.0
            nav.complete()
            val mouse = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
            val blank = Point(850, 620)
            mouse.mousePressed(event(MouseEvent.MOUSE_PRESSED, blank, diagram, MouseEvent.BUTTON3))
            mouse.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, Point(870, 630), diagram, MouseEvent.BUTTON3))
            mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, Point(870, 630), diagram, MouseEvent.BUTTON3))
            assertTrue(nav.hasChoice)
            mouse.mousePressed(event(MouseEvent.MOUSE_PRESSED, blank, diagram))
            mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, blank, diagram))
            assertTrue(nav.hasChoice)
            mouse.mouseWheelMoved(MouseWheelEvent(diagram, MouseEvent.MOUSE_WHEEL, 0, java.awt.event.InputEvent.CTRL_DOWN_MASK,
                blank.x, blank.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -2))
            assertTrue(nav.hasChoice)
            val zoom = diagram.zoom
            val center = diagram.center
            diagram.controls.routeButton.doClick()
            assertFalse(nav.hasChoice)
            assertEquals(zoom, diagram.zoom)
            assertEquals(center, diagram.center)
            assertEquals("save", diagram.currentNodeId)
            assertTrue(route.path.isEmpty())
            assertEquals(listOf("save"), commits)
        }

    @Test
    fun `cancelled and disposed graph candidates cannot commit`() =
        withCanvas(parallelGraph()) { diagram, route, nav, commits ->
            nav.stepFromSource(false)
            nav.cancel()
            assertFalse(nav.selectCandidateEdge("repeat"))
            assertTrue(commits.isEmpty())
            nav.stepFromSource(false)
            nav.close()
            assertFalse(nav.selectCandidateEdge("repeat"))
            assertEquals("a", route.currentNodeId)
            assertTrue(commits.isEmpty())
            assertNull(diagram.routeNavigation)
        }

    @Test
    fun `equal snapshot preserves pending choice while changed snapshot cancels it`() =
        withCanvas { diagram, route, nav, _ ->
            nav.select("save")
            nav.complete()
            val saved =
                BrowseState(diagram.record.id, route.currentNodeId, route.path, navigationState = route.navigationState)
            assertFalse(nav.synchronize("save", saved))
            assertTrue(nav.hasChoice)
            nav.stepFromSource(true)
            nav.synchronize(emptyList(), "email")
            assertEquals("email", route.currentNodeId)
            assertFalse(nav.hasChoice)
            assertTrue(diagram.choiceTargets.isEmpty())
        }

    @Test
    fun `ordinary keyboard candidates preserve the current target until confirmation`() =
        withCanvas { diagram, route, nav, commits ->
            nav.select("save")
            nav.complete()
            press(diagram, "next-node")
            assertEquals("wx", diagram.routeChoice.focusedNodeId)
            assertEquals("save", diagram.currentNodeId)
            assertEquals(listOf("save"), commits)
            press(diagram, "open-questions")
            assertEquals(listOf("wx-merge", "merge-save"), route.path)
        }

    @Test
    fun `invalid equal-node snapshots discard pending choices before rejecting stale candidates`() =
        withCanvas { diagram, route, nav, commits ->
            nav.select("save")
            nav.complete()
            nav.stepFromSource(true)
            val snapshot = BrowseState(diagram.record.id, "save", route.path, navigationState = route.navigationState)
            assertFalse(
                nav.synchronize(
                    "save", snapshot.copy(
                        navigationState = snapshot.navigationState?.copy(prefixNodeId = "missing"),
                    ),
                ),
            )
            assertFalse(nav.hasChoice)
            assertEquals(listOf("save"), commits)
            assertTrue(route.path.isEmpty())
        }

    @Test
    fun `repeated source and graph directions retain preview and opposite directions replace candidates`() =
        withCanvas(bidirectionalGraph()) { diagram, route, nav, commits ->
            nav.select("center")
            nav.stepFromSource(false)
            nav.previewEdge("out-2")
            nav.stepFromSource(false)
            diagram.controls.nextButton.doClick()
            assertEquals("after-2", diagram.routeChoice.focusedNodeId)
            assertEquals("center", route.currentNodeId)
            assertEquals(listOf("center"), commits)
            diagram.controls.previousButton.doClick()
            assertEquals(setOf("before-1", "before-2"), diagram.choiceTargets)
            assertEquals(setOf("in-1", "in-2"), diagram.routeChoice.edgeIds)
            nav.stepFromSource(false)
            assertEquals(setOf("after-1", "after-2"), diagram.choiceTargets)
            assertFalse(nav.selectCandidateEdge("in-1"))
            diagram.selectUnderstandingNode("after-2", notify = true)
            assertFalse(nav.hasChoice)
            assertEquals(listOf("out-2"), route.path)
            assertEquals(listOf("center", "after-2"), commits)
        }

    @Test
    fun `ordinary nodes cancel a source choice before navigating`() =
        withCanvas(bidirectionalGraph()) { diagram, route, nav, commits ->
            nav.select("center")
            nav.stepFromSource(false)
            diagram.selectUnderstandingNode("before-2", notify = true)
            assertFalse(nav.hasChoice)
            assertFalse(nav.selectCandidateEdge("out-1"))
            assertEquals("before-2", route.currentNodeId)
            assertEquals(listOf("center", "before-2"), commits)
        }

    private fun bidirectionalGraph() = UnderstandingGraph(
        listOf("before-1", "before-2", "center", "after-1", "after-2").map { GraphNode(it, it) },
        listOf(
            GraphEdge("in-1", "before-1", "center", "control_flow"),
            GraphEdge("in-2", "before-2", "center", "control_flow"),
            GraphEdge("out-1", "center", "after-1", "control_flow"),
            GraphEdge("out-2", "center", "after-2", "control_flow"),
        ),
    )

    @Test
    fun `ordinary incoming line click selects its route without the completion button`() =
        withCanvas { diagram, route, nav, _ ->
            nav.select("merge")
            val edge = diagram.layout.edges.first { it.edge.id == "wx-merge" }
            val point = edge.segments.map { screen(diagram, (it.x1 + it.x2) / 2, (it.y1 + it.y2) / 2) }
                .first { diagram.candidateEdgeAt(it) == "wx-merge" }
            val mouse = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
            mouse.mousePressed(event(MouseEvent.MOUSE_PRESSED, point, diagram))
            mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, point, diagram))
            assertEquals("merge", route.currentNodeId)
            assertEquals(listOf("wx-merge"), route.path)
            diagram.selectUnderstandingNode("merge", notify = true)
            assertEquals(listOf("wx-merge"), route.path)
            diagram.controls.routeButton.doClick()
            assertEquals(setOf("email-merge", "wx-merge"), diagram.routeChoice.edgeIds)
        }

    private fun parallelGraph() = UnderstandingGraph(
        listOf(GraphNode("a", "支付检查"), GraphNode("b", "返回")),
        listOf(
            GraphEdge("first", "a", "b", "control_flow", "首次支付"),
            GraphEdge("repeat", "a", "b", "control_flow", "重复回调"),
        ),
    )

    private fun press(diagram: UnderstandingDiagram, action: String) =
        diagram.actionMap.get(action).actionPerformed(ActionEvent(diagram, 0, action))

    private fun screen(diagram: UnderstandingDiagram, x: Double, y: Double) = Point(
        (diagram.width / 2.0 + (x - diagram.centerX) * diagram.zoom).toInt(),
        (diagram.height / 2.0 + (y - diagram.centerY) * diagram.zoom).toInt(),
    )

    private fun event(kind: Int, point: Point, component: java.awt.Component, button: Int = MouseEvent.BUTTON1) =
        MouseEvent(component, kind, 0, 0, point.x, point.y, 1, false, button)
}
