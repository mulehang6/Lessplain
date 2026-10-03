package com.master.understanding.diagram

import com.master.understanding.core.CompleteRoute
import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepResult
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramCompleteRouteNavigationTest {
    private val graph = UnderstandingGraph(listOf("a", "b", "target").map { GraphNode(it, it) },
        listOf(GraphEdge("at", "a", "target", "call"), GraphEdge("bt", "b", "target", "data_flow")))

    @Test fun `node click highlights all upstream edges statically and clears a confirmed route`() {
        SwingUtilities.invokeAndWait {
            val diagram = diagram()
            try {
                diagram.showUnderstandingPath(listOf("at"), "target", false)
                diagram.selectUnderstandingNode("target", notify = true)
                assertTrue(diagram.routeEdgeIds.isEmpty())
                assertEquals(setOf("at", "bt"), diagram.completeRouteNavigation.upstreamEdgeIds)
                assertFalse(diagram.flowTimer.isRunning)
            } finally { diagram.dispose() }
        }
    }

    @Test fun `preview and cancel never select a node or emit confirmed route callbacks`() {
        SwingUtilities.invokeAndWait {
            val diagram = diagram()
            try {
                diagram.showUnderstandingPath(emptyList(), "target", false)
                var selected = 0
                var confirmed = 0
                diagram.onNodeSelected { selected++ }
                diagram.onCompleteRouteSelected { _, _ -> confirmed++ }
                diagram.completeRouteNavigation.preview(CompleteRoute("a", listOf("at")))
                assertEquals(setOf("at"), diagram.completeRouteNavigation.previewEdgeIds)
                assertEquals("target", diagram.currentNodeId)
                assertTrue(diagram.routeEdgeIds.isEmpty())
                assertEquals(0, selected)
                assertEquals(0, confirmed)
                diagram.completeRouteNavigation.preview(null)
                assertNull(diagram.completeRouteNavigation.previewEdgeIds)
                assertEquals(setOf("at", "bt"), diagram.completeRouteNavigation.upstreamEdgeIds)
                assertTrue(diagram.completeRouteNavigation.confirm(CompleteRoute("b", listOf("bt")), "target"))
                assertEquals(setOf("bt"), diagram.routeEdgeIds)
                assertEquals(1, confirmed)
                assertEquals(0, selected)
                assertFalse(diagram.completeRouteNavigation.confirm(CompleteRoute("a", listOf("bt", "at")), "target"))
                assertEquals(setOf("bt"), diagram.routeEdgeIds)
            } finally { diagram.dispose() }
        }
    }

    @Test fun `a graph without zero indegree entries remains browsable without inventing a complete route`() {
        SwingUtilities.invokeAndWait {
            val cyclic = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")),
                listOf(GraphEdge("ab", "a", "b", "call"), GraphEdge("ba", "b", "a", "call")))
            val diagram = createUnderstandingDiagram(UnderstandingRecord("cycle", 1, null, null, "回路", "", null, cyclic, 0))
            try {
                val explorer = StaticExplorer(cyclic)
                assertEquals(StepResult.Moved("b"), explorer.next())
                diagram.showUnderstandingPath(explorer.path, explorer.currentNodeId, false)
                assertEquals(setOf("a", "b"), diagram.nodeIds)
                assertTrue(diagram.completeRouteNavigation.routes.entryNodeIds.isEmpty())
                assertTrue(diagram.routeEdgeIds.isEmpty())
                assertTrue(diagram.completeRouteNavigation.upstreamEdgeIds.isEmpty())
                assertFalse(diagram.completeRouteNavigation.confirm(CompleteRoute("a", listOf("ab")), "b"))
                assertEquals(StepResult.Moved("a"), explorer.next())
            } finally { diagram.dispose() }
        }
    }

    private fun diagram() = createUnderstandingDiagram(UnderstandingRecord("test", 1, null, null, "流程", "", null, graph, 0))

    @Test fun `upstream focus and full route confirmation share the existing source navigation owner`() {
        SwingUtilities.invokeAndWait {
            val diagram = diagram()
            val explorer = StaticExplorer(graph)
            val committed = mutableListOf<List<String>>()
            var menu: JPopupMenu? = null
            val navigation = DiagramRouteNavigation(diagram, explorer, { committed += explorer.path }, { menu = it })
            try {
                diagram.selectUnderstandingNode("target", notify = true)
                assertEquals(listOf(emptyList()), committed)
                assertTrue(explorer.path.isEmpty())
                assertEquals(setOf("at", "bt"), diagram.completeRouteNavigation.upstreamEdgeIds)
                diagram.completeRouteNavigation.preview(CompleteRoute("b", listOf("bt")))
                assertEquals(1, committed.size)
                assertTrue(diagram.completeRouteNavigation.confirm(CompleteRoute("b", listOf("bt")), "target"))
                assertEquals(listOf("bt"), explorer.path)
                assertEquals(listOf(emptyList(), listOf("bt")), committed)

                navigation.complete("target")
                assertTrue(navigation.hasChoice)
                requireNotNull(menu).components.filterIsInstance<JMenuItem>().first().doClick(0)
                assertEquals("target", diagram.currentNodeId)
                assertEquals(listOf("at"), explorer.path)
                assertEquals(listOf("at"), committed.last())
            } finally { navigation.close(); diagram.dispose() }
        }
    }

    @Test fun `stale revision cannot confirm a complete route through the source navigation owner`() {
        SwingUtilities.invokeAndWait {
            val diagram = diagram()
            var current = true
            var commits = 0
            val navigation = DiagramRouteNavigation(diagram, StaticExplorer(graph), { commits++ }, isCurrent = { current })
            try {
                diagram.selectUnderstandingNode("target", notify = true)
                current = false
                assertFalse(diagram.completeRouteNavigation.confirm(CompleteRoute("a", listOf("at")), "target"))
                assertEquals(1, commits)
                assertTrue(navigation.isClosed)
            } finally { navigation.close(); diagram.dispose() }
        }
    }

    @Test fun `combined follow up routes respect source records without hiding root cluster relations`() {
        SwingUtilities.invokeAndWait {
            val combined = graph.copy(edges = graph.edges + GraphEdge("ba", "b", "a", "call"))
            val diagram = createUnderstandingDiagram(UnderstandingRecord("combined", 1, "parent", null,
                "追问", "", null, combined, 0))
            val explorer = StaticExplorer(combined, scopes = mapOf("a" to "first", "target" to "first", "b" to "second"))
            val navigation = DiagramRouteNavigation(diagram, explorer, {})
            try {
                diagram.selectUnderstandingNode("target", notify = true)
                assertEquals(setOf("at"), diagram.completeRouteNavigation.upstreamEdgeIds)
                assertEquals(listOf(CompleteRoute("a", listOf("at"))),
                    diagram.completeRouteNavigation.routes.routesTo("target").asSequence().toList())
                assertFalse(diagram.completeRouteNavigation.confirm(CompleteRoute("b", listOf("bt")), "target"))
                assertTrue(diagram.completeRouteNavigation.confirm(CompleteRoute("a", listOf("at")), "target"))
                assertEquals(listOf("at"), explorer.path)
                assertEquals(setOf("at", "bt", "ba"), com.master.understanding.core.UpstreamRoutes(combined).edgesTo("target"))
            } finally { navigation.close(); diagram.dispose() }
        }
    }
}
