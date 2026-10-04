package com.master.understanding.diagram

import com.master.understanding.core.StaticExplorer
import com.master.understanding.ide.CanvasNavigationSessions
import com.master.understanding.model.BrowseState
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
            UnderstandingDiagram, StaticExplorer, DiagramRouteNavigation, MutableList<String>,
            MutableList<RouteChoiceTestRequest>
        ) -> Unit,
    ) = SwingUtilities.invokeAndWait {
        val layout = layoutUnderstandingGraph(g)
        val record = UnderstandingRecord("test", 1, null, null, "导航", "", null, g, 0)
        val diagram = UnderstandingDiagram(record, null, layoutComputer = { layout })
        val explorer = StaticExplorer(g)
        val commits = mutableListOf<String>()
        val requests = mutableListOf<RouteChoiceTestRequest>()
        diagram.layout = layout
        diagram.setSize(900, 650)
        val navigation = DiagramRouteNavigation(
            diagram, g, explorer,
            { commits += explorer.currentNodeId }, recordedRouteChoices(requests),
        )
        try {
            action(diagram, explorer, navigation, commits, requests)
        } finally {
            navigation.close(); diagram.dispose()
        }
    }

    @Test
    fun `graph completion highlights only and candidate node confirms a precise route`() =
        withCanvas { diagram, route, nav, commits, requests ->
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
            assertTrue(diagram.routeChoice.edgeIds.isEmpty())
            assertFalse(diagram.controls.previousButton.isEnabled)
            assertFalse(diagram.controls.nextButton.isEnabled)
            assertTrue(requests.isEmpty(), "图表入口不得弹出列表")
            diagram.selectUnderstandingNode("wx", notify = true)
            assertEquals("save", diagram.currentNodeId)
            assertEquals(listOf("wx-merge", "merge-save"), route.path)
            assertEquals(listOf("save", "save"), commits)
            assertTrue(diagram.choiceTargets.isEmpty())
            assertFalse(diagram.controls.routeButton.isVisible)
        }

    @Test
    fun `source entry reuses pending graph choice with its caption anchor and preview`() =
        withCanvas { diagram, route, nav, commits, requests ->
            nav.select("save")
            nav.complete()
            val snapshot = route.navigationState
            val editor = JPanel()
            val anchor = Point(380, 120)
            val sessions = CanvasNavigationSessions()
            val registration = sessions.register(
                matches = { r, n -> r == "test" && n == "save" }, busy = { nav.hasChoice },
                previous = diagram::previousStep, next = diagram::nextStep,
                sourceStep = nav::stepFromSource, cancel = nav::cancel,
            )
            try {
                assertTrue(sessions.step("test", "save", true, editor, anchor))
                assertEquals(1, requests.size)
                assertSame(editor, requests.single().component)
                assertEquals(anchor, requests.single().point)
                assertEquals(snapshot, route.navigationState)
                requests.single().preview("wx-merge")
                assertEquals("wx", diagram.routeChoice.focusedNodeId)
                assertEquals(setOf("email", "wx"), diagram.choiceTargets)
                assertEquals(listOf("save"), commits)
                requests.single().finished("wx-merge")
                assertEquals(listOf("wx-merge", "merge-save"), route.path)
                assertTrue(requests.single().closed)
                assertNull(diagram.routeChoice.focusedNodeId)
            } finally {
                registration.close()
            }
        }

    @Test
    fun `parallel candidates highlight edges and node click keeps the choice pending`() {
        withCanvas(parallelGraph()) { diagram, route, nav, commits, requests ->
            diagram.nextStep()
            assertEquals(setOf("first", "repeat"), diagram.routeChoice.edgeIds)
            assertTrue(requests.isEmpty())
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
            assertTrue(isGraphCandidateClick(press), "原生列表关闭不能提前取消图表候选")
            mouse.mousePressed(press)
            mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, point, diagram))
            assertEquals(listOf("repeat"), route.path)
            assertEquals(listOf("b"), commits)
            assertTrue(diagram.routeChoice.edgeIds.isEmpty())
        }
    }

    @Test
    fun `keyboard enumerates parallel edges and escape preserves committed route`() =
        withCanvas(parallelGraph()) { diagram, route, nav, commits, _ ->
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
    fun `cancel restores viewport and blank click cancels while background dragging does not`() =
        withCanvas { diagram, route, nav, commits, _ ->
            nav.select("save")
            diagram.zoom = 1.7
            diagram.centerX = 64.0
            diagram.centerY = 95.0
            nav.complete()
            val mouse = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
            val blank = Point(850, 620)
            mouse.mousePressed(event(MouseEvent.MOUSE_PRESSED, blank, diagram))
            mouse.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, Point(870, 630), diagram))
            mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, Point(870, 630), diagram))
            assertTrue(nav.hasChoice)
            mouse.mousePressed(event(MouseEvent.MOUSE_PRESSED, blank, diagram))
            mouse.mouseReleased(event(MouseEvent.MOUSE_RELEASED, blank, diagram))
            assertFalse(nav.hasChoice)
            assertEquals(1.7, diagram.zoom)
            assertEquals(Point(64, 95), Point(diagram.centerX.toInt(), diagram.centerY.toInt()))
            assertEquals("save", diagram.currentNodeId)
            assertTrue(route.path.isEmpty())
            assertEquals(listOf("save"), commits)
        }

    @Test
    fun `replaced cancelled and disposed source popups cannot commit or preview`() =
        withCanvas(parallelGraph()) { diagram, route, nav, commits, requests ->
            nav.stepFromSource(false, JPanel(), Point(100, 30))
            val replaced = requests.last()
            nav.stepFromSource(false, JPanel(), Point(180, 40))
            assertTrue(replaced.closed)
            replaced.preview("repeat")
            replaced.finished("repeat")
            assertEquals("a", route.currentNodeId)
            assertTrue(commits.isEmpty())
            val cancelled = requests.last()
            nav.cancel()
            cancelled.finished("repeat")
            assertTrue(commits.isEmpty())
            nav.stepFromSource(false, JPanel(), Point())
            val disposed = requests.last()
            nav.close()
            disposed.preview("repeat")
            disposed.finished("repeat")
            assertTrue(commits.isEmpty())
            assertNull(diagram.routeNavigation)
        }

    @Test
    fun `equal snapshot preserves pending choice while changed snapshot cancels it`() =
        withCanvas { diagram, route, nav, _, requests ->
            nav.select("save")
            nav.complete()
            val saved =
                BrowseState(diagram.record.id, route.currentNodeId, route.path, navigationState = route.navigationState)
            assertFalse(nav.synchronize("save", saved))
            assertTrue(nav.hasChoice)
            nav.stepFromSource(true, JPanel(), Point())
            val stale = requests.last()
            nav.synchronize(emptyList(), "email")
            stale.finished("wx-merge")
            assertEquals("email", route.currentNodeId)
            assertFalse(nav.hasChoice)
            assertTrue(diagram.choiceTargets.isEmpty())
        }

    @Test
    fun `ordinary keyboard candidates preserve the current target until confirmation`() =
        withCanvas { diagram, route, nav, commits, _ ->
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
    fun `invalid equal-node snapshots discard pending choices before rejecting stale callbacks`() =
        withCanvas { diagram, route, nav, commits, requests ->
            nav.select("save")
            nav.complete()
            nav.stepFromSource(true, JPanel(), Point())
            val snapshot = BrowseState(diagram.record.id, "save", route.path, navigationState = route.navigationState)
            val stale = requests.last()
            assertFalse(
                nav.synchronize(
                    "save", snapshot.copy(
                        navigationState = snapshot.navigationState?.copy(prefixNodeId = "missing"),
                    ),
                ),
            )
            assertFalse(nav.hasChoice)
            stale.finished("wx-merge")
            assertEquals(listOf("save"), commits)
            assertTrue(route.path.isEmpty())
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

    private fun event(kind: Int, point: Point, component: JPanel) =
        MouseEvent(component, kind, 0, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1)
}
