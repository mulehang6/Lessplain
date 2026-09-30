package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DiagramEdgeRouteRepairTest {
    @Test
    fun `parallel edges retain identities but separate arrows labels and long runs in both directions`() {
        for (direction in LayoutDirection.entries) {
            val graph = UnderstandingGraph(listOf(GraphNode("a", "入口"), GraphNode("b", "完成")),
                listOf(GraphEdge("main", "a", "b", "control_flow", "条件一"),
                    GraphEdge("parallel", "a", "b", "call", "条件二")))
            val layout = layoutUnderstandingGraph(graph, direction)
            assertEquals(graph.edges.map { it.id }, layout.edges.map { it.edge.id })
            assertClear(layout)
            assertNotEquals(layout.edges[0].arrowX to layout.edges[0].arrowY,
                layout.edges[1].arrowX to layout.edges[1].arrowY)
            val again = layoutUnderstandingGraph(graph, direction)
            assertEquals(signature(layout.edges), signature(again.edges))
        }
    }

    @Test
    fun `repair protects mainline and unchanged edges without moving any node`() {
        val nodes = nodes()
        val main = edge("main", "a", "b", listOf(100.0 to 56.0, 100.0 to 300.0))
        val parallel = main.copy(edge = main.edge.copy(id = "parallel", condition = "另一条路径"))
        val distant = edge("distant", "d", "e", listOf(800.0 to 56.0, 800.0 to 300.0))
        val repaired = repairEdgeRoutes(nodes, listOf(parallel, distant, main), setOf("main"))
        assertSame(main, repaired.single { it.edge.id == "main" })
        assertSame(distant, repaired.single { it.edge.id == "distant" })
        assertClear(UnderstandingGraphLayout(nodes, repaired, graphBounds(nodes.values, repaired)))
        assertEquals(signature(repaired), signature(repairEdgeRoutes(nodes,
            listOf(main, parallel, distant), setOf("main"))))
    }

    @Test
    fun `dragged obstacle re-routes unrelated edge and preserves far connector and all node positions`() {
        val nodes = nodes()
        val main = edge("main", "a", "b", listOf(100.0 to 56.0, 100.0 to 300.0))
        val distant = edge("distant", "d", "e", listOf(800.0 to 56.0, 800.0 to 300.0))
        val base = UnderstandingGraphLayout(nodes, listOf(main, distant), graphBounds(nodes.values, listOf(main, distant)))
        val graph = UnderstandingGraph(nodes.values.map { it.node }, base.edges.map { it.edge })
        val positions = mapOf("obstacle" to Point2D.Double(30.0, 150.0))
        val layout = positionUnderstandingNodes(graph, base, positions)
        assertEquals(30.0, layout.nodes.getValue("obstacle").bounds.x)
        assertEquals(150.0, layout.nodes.getValue("obstacle").bounds.y)
        nodes.filterKeys { it != "obstacle" }.forEach { (id, node) -> assertSame(node, layout.nodes.getValue(id)) }
        assertSame(distant, layout.edges.single { it.edge.id == "distant" })
        assertNotEquals(signature(listOf(main)), signature(layout.edges.filter { it.edge.id == "main" }))
        assertClear(layout)
    }

    @Test
    fun `moving endpoint separates parallel connectors and preserves route determinism`() {
        for (direction in LayoutDirection.entries) {
            val graph = complexGraph()
            val base = layoutUnderstandingGraph(graph, direction)
            val target = base.nodes.getValue("b").bounds
            val point = if (direction == LayoutDirection.VERTICAL)
                Point2D.Double(base.nodes.values.maxOf { it.bounds.maxX } + 80, target.y + 20)
            else Point2D.Double(target.x + 20, base.nodes.values.maxOf { it.bounds.maxY } + 80)
            val positions = mapOf("b" to point)
            val result = positionUnderstandingNodes(graph, base, positions)
            assertEquals(positions.getValue("b").x, result.nodes.getValue("b").bounds.x)
            assertEquals(positions.getValue("b").y, result.nodes.getValue("b").bounds.y)
            assertClear(result)
            assertEquals(signature(result.edges), signature(positionUnderstandingNodes(graph, base, positions).edges))
        }
    }

    @Test
    fun `overlapping user positions keep finite connectors and do not lose identities`() {
        val graph = complexGraph()
        val base = layoutUnderstandingGraph(graph)
        val destination = base.nodes.getValue("d").bounds
        val result = positionUnderstandingNodes(graph, base, mapOf("b" to Point2D.Double(destination.x, destination.y)))
        assertEquals(graph.edges.map { it.id }, result.edges.map { it.edge.id })
        assertTrue(result.edges.all { it.paintBounds.x.isFinite() && it.paintBounds.y.isFinite() })
    }

    @Test
    fun `blocked departure side can change ports without moving tightly packed nodes`() {
        val nodes = listOf(
            "a" to Rectangle2D.Double(0.0, 0.0, 200.0, 56.0),
            "b" to Rectangle2D.Double(700.0, 0.0, 200.0, 56.0),
            "blocker" to Rectangle2D.Double(206.0, 0.0, 200.0, 56.0),
        ).associate { (id, bounds) -> id to PlacedNode(GraphNode(id, id), bounds) }
        val original = edge("ab", "a", "b", listOf(200.0 to 28.0, 700.0 to 28.0))
        val repaired = repairEdgeRoutes(nodes, listOf(original), setOf("ab"))
        assertClear(UnderstandingGraphLayout(nodes, repaired, graphBounds(nodes.values, repaired)))
    }

    @Test
    fun `condition label cannot conceal its own arrow`() {
        val nodes = listOf("a" to Rectangle2D.Double(0.0, 0.0, 200.0, 56.0),
            "b" to Rectangle2D.Double(700.0, 0.0, 200.0, 56.0))
            .associate { (id, bounds) -> id to PlacedNode(GraphNode(id, id), bounds) }
        val original = edge("ab", "a", "b", listOf(200.0 to 28.0, 700.0 to 28.0))
        val covered = original.copy(edge = original.edge.copy(condition = "检查"),
            labelBounds = Rectangle2D.Double(680.0, 22.0, 18.0, 12.0))
        val repaired = repairEdgeRoutes(nodes, listOf(covered), setOf("ab")).single()
        assertSame(original.path, repaired.path)
        assertFalse(requireNotNull(repaired.labelBounds).intersects(repaired.arrowPath.bounds2D))
    }

    @Test
    fun `complex and clustered flows remain distinguishable through branches returns and loops`() {
        val graph = complexGraph()
        for (direction in LayoutDirection.entries) {
            assertClear(layoutUnderstandingGraph(graph, direction))
            val clusters = listOf(DiagramCluster("first", "请求", setOf("a", "b", "c")),
                DiagramCluster("second", "后续处理", setOf("d", "e")))
            val clustered = layoutUnderstandingClusters(graph, clusters, direction = direction)
            assertClear(clustered)
            val scene = EdgeRoutingObstacles(clustered.nodes.values, clusterRoutingObstacles(clustered.clusters))
            assertTrue(clustered.edges.none(scene::crossesNode))
        }
    }

    private fun complexGraph(): UnderstandingGraph = UnderstandingGraph(
        listOf("a", "b", "c", "d", "e").map { GraphNode(it, "步骤 $it") },
        listOf(GraphEdge("ab", "a", "b", "control_flow", "继续"),
            GraphEdge("parallel", "a", "b", "call", "调用"),
            GraphEdge("ac", "a", "c", "control_flow", "分支"),
            GraphEdge("bd", "b", "d", "control_flow"), GraphEdge("cd", "c", "d", "control_flow"),
            GraphEdge("de", "d", "e", "control_flow"), GraphEdge("back", "e", "b", "control_flow", "重试"),
            GraphEdge("self", "c", "c", "call", "循环")),
    )

    private fun nodes(): Map<String, PlacedNode> = listOf(
        "a" to Rectangle2D.Double(0.0, 0.0, 200.0, 56.0),
        "b" to Rectangle2D.Double(0.0, 300.0, 200.0, 56.0),
        "obstacle" to Rectangle2D.Double(350.0, 150.0, 200.0, 56.0),
        "d" to Rectangle2D.Double(700.0, 0.0, 200.0, 56.0),
        "e" to Rectangle2D.Double(700.0, 300.0, 200.0, 56.0),
    ).associate { (id, bounds) -> id to PlacedNode(GraphNode(id, id), bounds) }

    private fun edge(id: String, from: String, to: String, points: List<Pair<Double, Double>>): PlacedEdge {
        val path = Path2D.Double().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        val (x, y) = points.last()
        val (beforeX, beforeY) = points[points.lastIndex - 1]
        return PlacedEdge(GraphEdge(id, from, to, "control_flow"), path, x, y, x - beforeX, y - beforeY, 0.0, 0.0)
    }

    private fun assertClear(layout: UnderstandingGraphLayout) {
        val scene = EdgeRoutingObstacles(layout.nodes.values, emptyList())
        layout.edges.forEach(scene::add)
        layout.edges.forEach { edge ->
            assertFalse(scene.crossesNode(edge), "${edge.edge.id} 穿过节点")
            assertFalse(scene.labelCrossesNode(edge), "${edge.edge.id} 标签压住节点")
            assertEquals(false, edge.labelBounds?.intersects(edge.arrowPath.bounds2D) ?: false, "标签遮挡自己的箭头")
            assertTrue(scene.conflicts(edge).isEmpty(), "${edge.edge.id} 与 ${scene.conflicts(edge)} 冲突")
            assertTrue(edge.arrowDx != 0.0 || edge.arrowDy != 0.0, "箭头缺少方向")
            assertTrue(layout.nodes.getValue(edge.edge.to).bounds.intersects(
                edge.arrowX - 0.5, edge.arrowY - 0.5, 1.0, 1.0))
        }
        // Independent pairwise checks guard against regressions in the production conflict classifier itself.
        layout.edges.forEachIndexed { index, edge ->
            layout.edges.drop(index + 1).forEach { other ->
                if (edge.labelBounds != null && other.labelBounds != null) {
                    assertFalse(edge.labelBounds.intersects(other.labelBounds), "条件标签重叠")
                }
                assertFalse(edge.arrowPath.bounds2D.intersects(other.arrowPath.bounds2D), "箭头重叠")
                val first = straightPieces(edge)
                val second = straightPieces(other)
                first.forEach { a -> second.forEach { b ->
                    if (a[1] == a[3] && b[1] == b[3] && a[1] == b[1]) {
                        val overlap = minOf(maxOf(a[0], a[2]), maxOf(b[0], b[2])) -
                            maxOf(minOf(a[0], a[2]), minOf(b[0], b[2]))
                        assertTrue(overlap <= 12, "水平长段重合: ${edge.edge.id}/${other.edge.id}")
                    }
                    if (a[0] == a[2] && b[0] == b[2] && a[0] == b[0]) {
                        val overlap = minOf(maxOf(a[1], a[3]), maxOf(b[1], b[3])) -
                            maxOf(minOf(a[1], a[3]), minOf(b[1], b[3]))
                        assertTrue(overlap <= 12, "竖直长段重合: ${edge.edge.id}/${other.edge.id}")
                    }
                } }
            }
        }
    }

    private fun straightPieces(edge: PlacedEdge): List<List<Double>> = buildList {
        val iterator = edge.path.getPathIterator(null)
        val coordinates = DoubleArray(6)
        var previousX = 0.0
        var previousY = 0.0
        while (!iterator.isDone) {
            val kind = iterator.currentSegment(coordinates)
            if (kind == java.awt.geom.PathIterator.SEG_LINETO) {
                add(listOf(previousX, previousY, coordinates[0], coordinates[1]))
            }
            val offset = if (kind == java.awt.geom.PathIterator.SEG_QUADTO) 2 else 0
            previousX = coordinates[offset]
            previousY = coordinates[offset + 1]
            iterator.next()
        }
    }

    private fun signature(edges: List<PlacedEdge>): Map<String, Any> = edges.associate { edge ->
        edge.edge.id to listOf(edge.segments, edge.arrowX, edge.arrowY, edge.labelBounds)
    }
}
