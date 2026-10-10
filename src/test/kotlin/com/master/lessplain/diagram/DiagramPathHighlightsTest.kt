package com.master.lessplain.diagram

import com.master.lessplain.core.StaticExplorer
import com.master.lessplain.core.StepResult
import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingRecord
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramPathHighlightsTest {
    private val graph = UnderstandingGraph(listOf("a", "b", "target").map { GraphNode(it, it) },
        listOf(GraphEdge("at", "a", "target", "call"), GraphEdge("bt", "b", "target", "data_flow")))

    @Test fun `node click replaces selected path with all upstream highlights`() {
        SwingUtilities.invokeAndWait {
            val diagram = diagram()
            try {
                diagram.showUnderstandingPath(listOf("at"), "target", false)
                assertEquals(setOf("at"), diagram.highlightedEdgeIds)
                diagram.selectUnderstandingNode("target", notify = true)
                assertTrue(diagram.routeEdgeIds.isEmpty())
                assertEquals(setOf("at", "bt"), diagram.highlightedEdgeIds)
                // An offscreen canvas must not keep an animation timer running.
                assertFalse(diagram.flowTimer.isRunning)
            } finally { diagram.dispose() }
        }
    }

    @Test fun `a graph without zero indegree entries remains browsable`() {
        SwingUtilities.invokeAndWait {
            val cyclic = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")),
                listOf(GraphEdge("ab", "a", "b", "call"), GraphEdge("ba", "b", "a", "call")))
            val diagram = createUnderstandingDiagram(UnderstandingRecord("cycle", 1, null, null, "回路", "", null, cyclic, 0))
            try {
                val explorer = StaticExplorer(cyclic)
                assertEquals(StepResult.Moved("b"), explorer.next())
                diagram.showUnderstandingPath(explorer.path, explorer.currentNodeId, false)
                assertEquals(setOf("a", "b"), diagram.nodeIds)
                assertTrue(diagram.highlightedEdgeIds.isEmpty())
                assertEquals(StepResult.Moved("a"), explorer.next())
            } finally { diagram.dispose() }
        }
    }

    @Test fun `upstream focus and explicit completion retain the same source navigation owner`() {
        SwingUtilities.invokeAndWait {
            val diagram = diagram()
            val explorer = StaticExplorer(graph)
            val committed = mutableListOf<List<String>>()
            val navigation = DiagramRouteNavigation(diagram, explorer, { committed += explorer.path })
            try {
                diagram.selectUnderstandingNode("target", notify = true)
                assertEquals(listOf(emptyList()), committed)
                assertEquals(setOf("at", "bt"), diagram.highlightedEdgeIds)
                navigation.complete()
                assertTrue(navigation.hasChoice)
                navigation.stepFromSource(true)
                diagram.selectUnderstandingNode("a", notify = true)
                assertEquals("target", diagram.currentNodeId)
                assertEquals(listOf("at"), explorer.path)
                assertEquals(listOf(emptyList(), listOf("at")), committed)
                assertEquals(setOf("at"), diagram.highlightedEdgeIds)
            } finally { navigation.close(); diagram.dispose() }
        }
    }

    @Test fun `combined follow up highlights respect source records`() {
        SwingUtilities.invokeAndWait {
            val combined = graph.copy(edges = graph.edges + GraphEdge("ba", "b", "a", "call"))
            val diagram = createUnderstandingDiagram(UnderstandingRecord("combined", 1, "parent", null,
                "追问", "", null, combined, 0))
            val explorer = StaticExplorer(combined, scopes = mapOf("a" to "first", "target" to "first", "b" to "second"))
            val navigation = DiagramRouteNavigation(diagram, explorer, {})
            try {
                diagram.selectUnderstandingNode("target", notify = true)
                assertEquals(setOf("at"), diagram.highlightedEdgeIds)
                navigation.complete()
                assertEquals(listOf("at"), explorer.path)
                assertEquals(setOf("at"), diagram.highlightedEdgeIds)
                assertEquals(setOf("at", "bt", "ba"), com.master.lessplain.core.UpstreamRoutes(combined).edgesTo("target"))
            } finally { navigation.close(); diagram.dispose() }
        }
    }

    private fun diagram() = createUnderstandingDiagram(UnderstandingRecord("test", 1, null, null, "流程", "", null, graph, 0))
}
