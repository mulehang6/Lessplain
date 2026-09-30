package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ElkGraphQualityTest {
    private val flow = UnderstandingGraph(
        (0..15).map { GraphNode("step-$it", "处理步骤 $it，核对条件并继续") } +
            GraphNode("isolated", "独立说明"),
        (0 until 15).map { GraphEdge("next-$it", "step-$it", "step-${it + 1}", "control_flow") } +
            listOf(
                GraphEdge("branch-a", "step-2", "step-5", "control_flow", "有效请求"),
                GraphEdge("branch-b", "step-2", "step-4", "control_flow", "需要重试"),
                GraphEdge("join", "step-4", "step-7", "control_flow"),
                GraphEdge("back", "step-10", "step-6", "control_flow", "继续循环"),
                GraphEdge("parallel", "step-2", "step-5", "call"),
                GraphEdge("self", "step-12", "step-12", "call"),
            ),
    )

    @Test
    fun `long chain leaves connector space between vertical layers`() {
        val vertical = layoutUnderstandingGraph(flow, LayoutDirection.VERTICAL)
        val gaps = (0 until 15).map { index ->
            val source = vertical.nodes.getValue("step-$index").bounds
            val target = vertical.nodes.getValue("step-${index + 1}").bounds
            target.minY - source.maxY
        }.filter { it > 0 }
        assertTrue(gaps.isNotEmpty())
        assertTrue(gaps.min() >= 50.0, "相邻层连接空间不足: ${gaps.min()}")
    }

    @Test
    fun `horizontal mainline stays level through a side branch`() {
        val graph = UnderstandingGraph(
            (0..10).map { GraphNode("n$it", "处理步骤 $it") } +
                (0..2).map { GraphNode("side$it", "分支步骤 $it") },
            (0 until 10).map { GraphEdge("next$it", "n$it", "n${it + 1}", "control_flow") } +
                listOf(
                    GraphEdge("side0", "n2", "side0", "control_flow", "另一种情况"),
                    GraphEdge("side1", "side0", "side1", "control_flow"),
                    GraphEdge("side2", "side1", "side2", "control_flow"),
                    GraphEdge("join", "side2", "n7", "control_flow"),
                ),
        )
        val layout = layoutUnderstandingGraph(graph, LayoutDirection.HORIZONTAL)
        val y = (0..10).map { layout.nodes.getValue("n$it").bounds.centerY }
        assertTrue(y.max() - y.min() < 20.0, "横向主线呈阶梯状: $y")
    }

    @Test
    fun `complex flow keeps every shape finite and routes around unrelated nodes`() {
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingGraph(flow, direction)
            assertEquals(flow.nodes.size, layout.nodes.size)
            assertEquals(flow.edges.size, layout.edges.size)
            assertTrue(layout.nodes.values.all { it.bounds.x.isFinite() && it.bounds.y.isFinite() })
            layout.nodes.values.forEach { first ->
                layout.nodes.values.filter { it !== first }.forEach { other ->
                    assertFalse(first.bounds.intersects(other.bounds), "${first.node.id} 与 ${other.node.id} 重叠")
                }
            }
            layout.edges.forEach { edge ->
                val source = layout.nodes.getValue(edge.edge.from).bounds
                val target = layout.nodes.getValue(edge.edge.to).bounds
                assertTrue(onBoundary(source, firstPoint(edge)), "${edge.edge.id} 起点不在节点边界")
                assertTrue(onBoundary(target, edge.arrowX to edge.arrowY), "${edge.edge.id} 箭头不在节点边界")
                layout.nodes.values.filter { it.node.id != edge.edge.from && it.node.id != edge.edge.to }
                    .forEach { other ->
                        assertFalse(crosses(edge, other.bounds), "${edge.edge.id} 穿过 ${other.node.id}")
                        edge.labelBounds?.let { label ->
                            assertFalse(label.intersects(other.bounds), "${edge.edge.id} 标签压住 ${other.node.id}")
                        }
                    }
            }
            val again = layoutUnderstandingGraph(flow, direction)
            assertEquals(layout.nodes.mapValues { it.value.bounds }, again.nodes.mapValues { it.value.bounds })
            assertEquals(layout.edges.map { it.path.bounds2D }, again.edges.map { it.path.bounds2D })
        }
    }

    @Test
    fun `cross cluster relation survives grouping`() {
        val clusters = listOf(
            DiagramCluster("a", "第一段", (0..7).mapTo(hashSetOf()) { "step-$it" }),
            DiagramCluster("b", "第二段", (8..15).mapTo(hashSetOf()) { "step-$it" }),
        )
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(flow, clusters, direction = direction)
            assertEquals(flow.nodes.size, layout.nodes.size)
            assertEquals(flow.edges.size, layout.edges.size)
            val cross = layout.edges.single { it.edge.from == "step-7" && it.edge.to == "step-8" }
            assertTrue(onBoundary(layout.nodes.getValue("step-8").bounds, cross.arrowX to cross.arrowY))
            layout.clusters.forEach { group ->
                val title = DiagramClusterText.titleBounds(group)
                group.nodeIds.mapNotNull(layout.nodes::get).forEach { node ->
                    assertFalse(title.intersects(node.bounds), "${group.id} 标题压住 ${node.node.id}")
                }
            }
        }
    }

    @Test
    fun `example panel stays clear of graph nodes with a cross cluster relation`() {
        val groups = listOf(
            DiagramCluster("a", "请求来源", (0..7).mapTo(hashSetOf()) { "step-$it" }),
            DiagramCluster("b", "后续处理", (8..15).mapTo(hashSetOf()) { "step-$it" }),
        )
        val panel = DiagramExamplePanel("a", "username", "alice", emptySet(), emptyMap())
        val layout = layoutUnderstandingClusters(flow, groups, listOf(panel))
        val example = layout.clusters.first().exampleBounds ?: error("缺少示例面板")
        assertEquals(flow.edges.size, layout.edges.size)
        assertTrue(layout.clusters.first().bounds.contains(example))
        assertTrue(layout.nodes.values.none { it.bounds.intersects(example) })
        assertTrue(layout.clusters.drop(1).none { it.bounds.intersects(example) })
    }

    private fun firstPoint(edge: PlacedEdge): Pair<Double, Double> {
        val coordinates = DoubleArray(6)
        val path = edge.path.getPathIterator(null)
        assertEquals(PathIterator.SEG_MOVETO, path.currentSegment(coordinates))
        return coordinates[0] to coordinates[1]
    }

    private fun onBoundary(rectangle: Rectangle2D, point: Pair<Double, Double>): Boolean {
        val (x, y) = point
        if (x < rectangle.minX - 1 || x > rectangle.maxX + 1 ||
            y < rectangle.minY - 1 || y > rectangle.maxY + 1) return false
        return listOf(kotlin.math.abs(x - rectangle.minX), kotlin.math.abs(x - rectangle.maxX),
            kotlin.math.abs(y - rectangle.minY), kotlin.math.abs(y - rectangle.maxY)).min() < 1
    }

    private fun crosses(edge: PlacedEdge, rectangle: Rectangle2D): Boolean {
        val iterator = FlatteningPathIterator(edge.path.getPathIterator(null), 1.0)
        val coordinates = DoubleArray(6)
        var previous: Pair<Double, Double>? = null
        while (!iterator.isDone) {
            val kind = iterator.currentSegment(coordinates)
            val next = coordinates[0] to coordinates[1]
            if (kind == PathIterator.SEG_LINETO && previous?.let { (x, y) ->
                rectangle.intersectsLine(x, y, next.first, next.second)
            } == true) return true
            previous = next
            iterator.next()
        }
        return false
    }
}
