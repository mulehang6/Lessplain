package com.lessplain.diagram

import com.lessplain.core.StaticExplorer
import com.lessplain.model.BrowseState
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.RouteNavigationState
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import org.junit.jupiter.api.Test
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
        ),
    )

    @Test
    fun `source controls highlight graph candidates and cancellation preserves upstream paths`() =
        withDiagram { diagram ->
            val explorer = StaticExplorer(graph)
            var commits = 0
            val navigation = DiagramRouteNavigation(diagram, explorer, { commits++ })
            try {
                diagram.selectUnderstandingNode("d", notify = true)
                assertEquals(setOf("ab", "ac", "bd", "cd"), diagram.highlightedEdgeIds)
                navigation.stepFromSource(true)
                assertEquals(setOf("b", "c"), diagram.choiceTargets)
                assertEquals(setOf("bd", "cd"), diagram.routeChoice.edgeIds)
                navigation.cancel()
                assertEquals("d", explorer.currentNodeId)
                assertEquals(1, commits)
                assertTrue(diagram.choiceTargets.isEmpty())
                assertEquals(setOf("ab", "ac", "bd", "cd"), diagram.highlightedEdgeIds)
                navigation.stepFromSource(true)
                diagram.selectUnderstandingNode("c", notify = true)
                assertEquals("c", diagram.currentNodeId)
                assertEquals(setOf("ac"), diagram.highlightedEdgeIds)
                navigation.stepFromSource(false)
                assertEquals(setOf("ac", "cd"), diagram.highlightedEdgeIds)
                navigation.stepFromSource(true)
                assertEquals(setOf("b", "c"), diagram.choiceTargets)
            } finally { navigation.close() }
        }

    @Test
    fun `source feedback preserves the ambiguous prefix and new selection discards candidates`() =
        withDiagram { diagram ->
            val explorer = StaticExplorer(graph, savedNodeId = "d")
            var commits = 0
            val navigation = DiagramRouteNavigation(diagram, explorer, { commits++ })
            try {
                navigation.stepFromSource(false)
                val browse = BrowseState("test", "e", explorer.path, navigationState = explorer.navigationState)
                assertFalse(navigation.synchronize("e", browse))
                assertEquals(setOf("ab", "ac", "bd", "cd", "de"), diagram.highlightedEdgeIds)
                navigation.stepFromSource(true)
                navigation.stepFromSource(true)
                diagram.selectUnderstandingNode("b", notify = true)
                val expected = explorer.navigationState
                assertFalse(navigation.selectCandidateEdge("cd"))
                assertEquals(expected, explorer.navigationState)
                assertEquals(setOf("ab"), diagram.highlightedEdgeIds)
                val fromSource = BrowseState(
                    "test", "d", listOf("ab", "bd"),
                    navigationState = RouteNavigationState("a", listOf("ab", "bd")),
                )
                assertTrue(navigation.synchronize("d", fromSource))
                assertEquals(setOf("ab", "bd"), diagram.highlightedEdgeIds)
                assertEquals(3, commits)
            } finally { navigation.close() }
        }

    @Test
    fun `direct reselect preserves the chosen route and disposal disables its old candidates`() =
        withDiagram { diagram ->
            val explorer = StaticExplorer(graph)
            val navigation = DiagramRouteNavigation(diagram, explorer, {})
            navigation.stepFromSource(false)
            diagram.selectUnderstandingNode("b", notify = true)
            navigation.stepFromSource(false)
            assertEquals(setOf("ab", "bd"), diagram.highlightedEdgeIds)
            diagram.selectUnderstandingNode("d", notify = true)
            assertEquals(setOf("ab", "bd"), diagram.highlightedEdgeIds)
            navigation.stepFromSource(true)
            val snapshot = explorer.navigationState
            navigation.close()
            assertFalse(navigation.selectCandidateEdge("bd"))
            assertEquals(snapshot, explorer.navigationState)
            assertFalse(diagram.controls.previousButton.isVisible)
        }

    @Test
    fun `a graph revision change rejects its pending candidates and later steps`() = withDiagram { diagram ->
        val explorer = StaticExplorer(graph)
        var current = true
        var commits = 0
        val navigation = DiagramRouteNavigation(
            diagram, explorer, { commits++ }, isCurrent = { current },
        )
        try {
            navigation.stepFromSource(false)
            val snapshot = explorer.navigationState
            current = false
            navigation.selectCandidateEdge("ab")
            navigation.stepFromSource(false)
            diagram.selectUnderstandingNode("d", notify = true)
            assertFalse(navigation.synchronize("d", BrowseState("test", "d")))
            assertEquals(snapshot, explorer.navigationState)
            assertEquals(0, commits)
            assertTrue(diagram.choiceTargets.isEmpty())
        } finally { navigation.close() }
    }

    private fun withDiagram(block: (UnderstandingDiagram) -> Unit) = SwingUtilities.invokeAndWait {
        val layout = layoutUnderstandingGraph(graph)
        val record = UnderstandingRecord("test", 1, null, null, "导航", "", null, graph, 0)
        val diagram = UnderstandingDiagram(record, null, layoutComputer = { layout })
        diagram.layout = layout
        try { block(diagram) } finally { diagram.dispose() }
    }
}
