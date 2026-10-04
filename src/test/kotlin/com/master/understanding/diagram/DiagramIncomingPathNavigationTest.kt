package com.master.understanding.diagram

import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepDirection
import com.master.understanding.model.BrowseState
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.RouteNavigationState
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramIncomingPathNavigationTest {
    private val graph = UnderstandingGraph(
        listOf("a", "b", "c", "d", "e").map { GraphNode(it, it) }, listOf(
            GraphEdge("ab", "a", "b", "control_flow"), GraphEdge("ac", "a", "c", "control_flow"),
            GraphEdge("bd", "b", "d", "call"), GraphEdge("cd", "c", "d", "data_flow"),
            GraphEdge("de", "d", "e", "control_flow"),
        )
    )

    @Test
    fun `graph buttons ask by direction and cancellation leaves the clicked upstream paths intact`() =
        withDiagram { diagram ->
            val explorer = StaticExplorer(graph)
            var callback: ((String?) -> Unit)? = null
            val requested = mutableListOf<StepDirection>()
            var commits = 0
            val navigation = DiagramRouteNavigation(
                diagram, graph, explorer, { commits++ },
                RouteChoicePresenter { _, direction, edges, _, _, _, finished ->
                    requested += direction
                    assertEquals(
                        if (direction == StepDirection.PREVIOUS) listOf("bd", "cd") else listOf("ab", "ac"),
                        edges.map { it.id })
                    callback = finished
                    AutoCloseable { }
                })
            try {
                diagram.selectUnderstandingNode("d", notify = true)
                assertEquals(setOf("ab", "ac", "bd", "cd"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
                navigation.stepFromSource(true, diagram, java.awt.Point())
                assertEquals(setOf("b", "c"), diagram.choiceTargets)
                callback?.invoke(null)
                assertEquals("d", explorer.currentNodeId)
                assertEquals(1, commits)
                assertTrue(diagram.choiceTargets.isEmpty())
                navigation.stepFromSource(true, diagram, java.awt.Point())
                callback?.invoke("cd")
                assertEquals("c", diagram.currentNodeId)
                assertEquals(setOf("ac"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
                navigation.stepFromSource(false, diagram, java.awt.Point())
                assertEquals(setOf("ac", "cd"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
                navigation.stepFromSource(true, diagram, java.awt.Point())
                assertEquals(listOf(StepDirection.PREVIOUS, StepDirection.PREVIOUS, StepDirection.PREVIOUS), requested)
            } finally {
                navigation.close()
            }
        }

    @Test
    fun `source feedback preserves the ambiguous prefix and stale menus cannot change a new selection`() =
        withDiagram { diagram ->
            val explorer = StaticExplorer(graph, savedNodeId = "d")
            var callback: ((String?) -> Unit)? = null
            var commits = 0
            val navigation = DiagramRouteNavigation(
                diagram, graph, explorer, { commits++ },
                RouteChoicePresenter { _, _, _, _, _, _, finished -> callback = finished; AutoCloseable { } })
            try {
                navigation.stepFromSource(false, diagram, java.awt.Point())
                val browse = BrowseState("test", "e", explorer.path, navigationState = explorer.navigationState)
                assertFalse(navigation.synchronize("e", browse))
                assertEquals(setOf("ab", "ac", "bd", "cd", "de"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
                navigation.stepFromSource(true, diagram, java.awt.Point())
                navigation.stepFromSource(true, diagram, java.awt.Point())
                val stale = callback
                diagram.selectUnderstandingNode("b", notify = true)
                val expected = explorer.navigationState
                stale?.invoke("cd")
                assertEquals(expected, explorer.navigationState)
                assertEquals(setOf("ab"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
                val fromSource = BrowseState(
                    "test", "d", listOf("ab", "bd"),
                    navigationState = RouteNavigationState("a", listOf("ab", "bd"))
                )
                assertTrue(navigation.synchronize("d", fromSource))
                assertEquals(setOf("ab", "bd"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
                assertEquals(3, commits)
            } finally {
                navigation.close()
            }
        }

    @Test
    fun `direct reselect unfolds the chosen route and disposal disables its old choices`() = withDiagram { diagram ->
        val explorer = StaticExplorer(graph)
        var callback: ((String?) -> Unit)? = null
        val navigation = DiagramRouteNavigation(
            diagram, graph, explorer, {},
            RouteChoicePresenter { _, _, _, _, _, _, finished -> callback = finished; AutoCloseable { } })
        navigation.stepFromSource(false, diagram, java.awt.Point())
        callback?.invoke("ab")
        navigation.stepFromSource(false, diagram, java.awt.Point())
        assertEquals(setOf("ab", "bd"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
        diagram.selectUnderstandingNode("d", notify = true)
        assertEquals(setOf("ab", "ac", "bd", "cd"), (diagram.routeEdgeIds + diagram.pathHighlights.upstreamEdgeIds))
        navigation.stepFromSource(true, diagram, java.awt.Point())
        val snapshot = explorer.navigationState
        navigation.close()
        callback?.invoke("bd")
        assertEquals(snapshot, explorer.navigationState)
        assertFalse(previous(diagram).isVisible)
    }

    @Test
    fun `a choice completed synchronously releases its menu handle`() = withDiagram { diagram ->
        val explorer = StaticExplorer(graph)
        var released = 0
        val navigation = DiagramRouteNavigation(
            diagram, graph, explorer, {},
            RouteChoicePresenter { _, _, _, _, _, _, finished ->
                finished("ab")
                AutoCloseable { released++ }
            })
        try {
            navigation.stepFromSource(false, diagram, java.awt.Point())
            assertEquals("b", explorer.currentNodeId)
            assertEquals(1, released)
        } finally {
            navigation.close()
        }
        assertEquals(1, released)
    }

    @Test
    fun `a graph revision change rejects its pending choices and later steps`() = withDiagram { diagram ->
        val explorer = StaticExplorer(graph)
        var callback: ((String?) -> Unit)? = null
        var current = true
        var commits = 0
        val navigation = DiagramRouteNavigation(
            diagram, graph, explorer, { commits++ },
            RouteChoicePresenter { _, _, _, _, _, _, finished -> callback = finished; AutoCloseable { } },
            isCurrent = { current },
        )
        try {
            navigation.stepFromSource(false, diagram, java.awt.Point())
            val snapshot = explorer.navigationState
            current = false
            callback?.invoke("ab")
            navigation.stepFromSource(false, diagram, java.awt.Point())
            diagram.selectUnderstandingNode("d", notify = true)
            assertFalse(navigation.synchronize("d", BrowseState("test", "d")))
            assertEquals(snapshot, explorer.navigationState)
            assertEquals(0, commits)
            assertTrue(diagram.choiceTargets.isEmpty())
        } finally {
            navigation.close()
        }
    }

    @Test
    fun `parallel conditional edges have separately selectable labels`() {
        val edges = listOf(
            GraphEdge("one", "a", "d", "control_flow", "首次"),
            GraphEdge("two", "a", "d", "control_flow", "重试")
        )
        val items = routeChoiceItems(graph, edges)
        assertEquals(listOf("one", "two"), items.map { it.edgeId })
        assertTrue(items[0].label.contains("首次"))
        assertTrue(items[1].label.contains("重试"))
        assertTrue(items.all { it.label.contains("a → d") })
    }

    private fun withDiagram(block: (UnderstandingDiagram) -> Unit) {
        SwingUtilities.invokeAndWait {
            val layout = layoutUnderstandingGraph(graph)
            val record = UnderstandingRecord("test", 1, null, null, "导航", "", null, graph, 0)
            val diagram = UnderstandingDiagram(record, null, layoutComputer = { layout })
            diagram.layout = layout
            try {
                block(diagram)
            } finally {
                diagram.dispose()
            }
        }
    }

    private fun previous(diagram: UnderstandingDiagram) = diagram.components.filterIsInstance<JButton>()
        .single { it.accessibleContext.accessibleName == "上一步" }

    private fun next(diagram: UnderstandingDiagram) = diagram.components.filterIsInstance<JButton>()
        .single { it.accessibleContext.accessibleName == "下一步" }
}
