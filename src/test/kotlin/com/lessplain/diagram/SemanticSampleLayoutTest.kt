package com.lessplain.diagram

import java.awt.geom.Rectangle2D
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticSampleLayoutTest {
    @Test fun `semantic sample cards retain all identities and bounded connected geometry in both directions`() {
        for (name in listOf("article-publish-clusters", "paid-article-clusters")) {
            val (base, _) = groupedFlowFixture(name)
            val graph = semanticSampleFixture(name)
            assertEquals(base.nodes.map { it.id }, graph.nodes.map { it.id })
            assertEquals(base.edges, graph.edges)
            assertEquals(base.evidence, graph.evidence)
            for (direction in LayoutDirection.entries) {
                val layout = layoutUnderstandingGraph(graph, direction)
                assertEquals(graph.nodes.size, layout.nodes.size)
                assertEquals(graph.edges.size, layout.edges.size)
                assertTrue(layout.clusters.isEmpty())
                layout.nodes.values.forEach { node ->
                    assertTrue(layout.bounds.contains(node.bounds))
                    layout.nodes.values.filter { it !== node }.forEach { other ->
                        assertFalse(node.bounds.intersects(other.bounds))
                    }
                }
                layout.edges.forEach { edge ->
                    assertTrue(onBoundary(layout.nodes.getValue(edge.edge.to).bounds, edge.arrowX, edge.arrowY), "${edge.edge.id} 箭头未连接节点")
                }
                val second = layoutUnderstandingGraph(graph, direction)
                assertEquals(layout.nodes.mapValues { it.value.bounds }, second.nodes.mapValues { it.value.bounds })
                assertEquals(layout.edges.map { it.path.bounds2D }, second.edges.map { it.path.bounds2D })
            }
        }
    }

    private fun onBoundary(bounds: Rectangle2D, x: Double, y: Double): Boolean =
        (x >= bounds.minX - 2 && x <= bounds.maxX + 2 &&
            (abs(y - bounds.minY) < 2 || abs(y - bounds.maxY) < 2)) ||
            (y >= bounds.minY - 2 && y <= bounds.maxY + 2 &&
                (abs(x - bounds.minX) < 2 || abs(x - bounds.maxX) < 2))
}
