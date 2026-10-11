package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramCandidateEdgeHitTestingTest {
    @Test
    fun `unlabeled parallel relationships have distinct mouse targets before and after node movement`() {
        val graph = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")),
            listOf(GraphEdge("first", "a", "b", "call"), GraphEdge("second", "a", "b", "data_flow")),)
        val base = layoutUnderstandingGraph(graph)
        val a = base.nodes.getValue("a").bounds
        val moved = positionUnderstandingNodes(graph, base, mapOf("a" to Point2D.Double(a.x + 60, a.y)))
        val ids = setOf("first", "second")
        for (layout in listOf(base, moved)) {
            for (edge in layout.edges) {
                val points = edge.segments.map { Point2D.Double((it.x1 + it.x2) / 2, (it.y1 + it.y2) / 2) }
                    .filter { point -> layout.nodes.values.none { it.bounds.contains(point) } }
                assertTrue(points.any { candidateEdgeAt(layout.edges, ids, it, 6.0) == edge.edge.id },
                    "每条无条件标签的候选边都必须有独立的鼠标命中区域",)
            }
        }
    }

    @Test
    fun `candidate hit testing picks exact labels and nearest lanes at multiple zooms`() {
        val upper = edge("upper", 30.0, Rectangle2D.Double(50.0, 20.0, 40.0, 16.0))
        val lower = edge("lower", 60.0, Rectangle2D.Double(50.0, 55.0, 40.0, 16.0))
        val unrelated = edge("other", 32.0, null)
        val edges = listOf(upper, lower, unrelated)
        for (zoom in listOf(0.5, 1.0, 2.0)) {
            assertEquals(
                "upper",
                candidateEdgeAt(edges, setOf("upper", "lower"), Point2D.Double(20.0, 30.0), 6.0 / zoom),
            )
            assertEquals(
                "lower",
                candidateEdgeAt(edges, setOf("upper", "lower"), Point2D.Double(70.0, 64.0), 6.0 / zoom),
            )
            assertNull(candidateEdgeAt(edges, setOf("upper"), Point2D.Double(20.0, 60.0), 6.0 / zoom))
        }
    }

    @Test
    fun `shared connector segments do not silently pick a parallel relationship`() {
        val first = edge("first", 30.0, Rectangle2D.Double(40.0, 10.0, 30.0, 16.0))
        val second = edge("second", 30.0, Rectangle2D.Double(40.0, 50.0, 30.0, 16.0))
        val ids = setOf("first", "second")
        assertNull(candidateEdgeAt(listOf(first, second), ids, Point2D.Double(20.0, 30.0), 6.0))
        assertEquals("first", candidateEdgeAt(listOf(first, second), ids, Point2D.Double(50.0, 18.0), 6.0))
        assertEquals("second", candidateEdgeAt(listOf(first, second), ids, Point2D.Double(50.0, 58.0), 6.0))
    }

    private fun edge(id: String, y: Double, label: Rectangle2D.Double?): PlacedEdge {
        val path = Path2D.Double().apply { moveTo(10.0, y); lineTo(110.0, y) }
        return PlacedEdge(
            GraphEdge(id, "a", "b", "control_flow", if (label == null) null else id),
            path, 110.0, y, 100.0, 0.0, 0.0, 0.0, label,
        )
    }
}
