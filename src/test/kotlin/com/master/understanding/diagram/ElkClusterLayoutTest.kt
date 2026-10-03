package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.validate
import java.awt.geom.Rectangle2D
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Group arrangement follows the actual relation graph, not the payload's group order. */
class ElkClusterLayoutTest {
    private val sizes = linkedMapOf("main" to 10, "search" to 8, "stats" to 2, "rank" to 1, "sitemap" to 2)
    private val groups = sizes.map { (id, count) ->
        DiagramCluster(id, "处理阶段 $id", (0 until count).mapTo(linkedSetOf()) { "$id-$it" })
    }
    private val graph = UnderstandingGraph(
        sizes.flatMap { (id, count) -> (0 until count).map { GraphNode("$id-$it", "$id 处理 $it") } },
        sizes.flatMap { (id, count) -> (0 until count - 1).map {
            GraphEdge("$id-next-$it", "$id-$it", "$id-${it + 1}", "control_flow")
        } } + sizes.keys.drop(1).map {
            GraphEdge("dispatch-$it", "main-7", "$it-0", "call")
        },
    )

    @Test
    fun `broadcast consumers occupy one downstream layer in both directions`() {
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(graph, groups, direction = direction)
            val boxes = layout.clusters.associateBy { it.id }
            val main = boxes.getValue("main").bounds
            val consumers = sizes.keys.drop(1).map { boxes.getValue(it).bounds }
            val starts = consumers.map { if (direction == LayoutDirection.VERTICAL) it.y else it.x }
            assertTrue(starts.max() - starts.min() < 100.0,
                "$direction 将独立消费者强制串行排列: $starts")
            consumers.forEach { bounds ->
                assertTrue(if (direction == LayoutDirection.VERTICAL) bounds.y > main.maxY else bounds.x > main.maxX)
            }
            assertGeometry(layout)
        }
    }

    @Test
    fun `single step consumer does not acquire the height of its neighbours`() {
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(graph, groups, direction = direction)
            val rank = layout.clusters.single { it.id == "rank" }.bounds
            val node = layout.nodes.getValue("rank-0").bounds
            assertTrue(rank.height < node.height + 180.0, "$direction 单节点组存在大量空白: $rank")
            assertTrue(rank.width < node.width + 200.0, "$direction 单节点组存在大量空白: $rank")
        }
    }

    @Test
    fun `group list order does not create dependencies between independent consumers`() {
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(graph, groups.reversed(), direction = direction)
            val main = layout.clusters.single { it.id == "main" }.bounds
            layout.clusters.filter { it.id != "main" }.forEach { consumer ->
                assertTrue(if (direction == LayoutDirection.VERTICAL) consumer.bounds.y > main.maxY
                    else consumer.bounds.x > main.maxX, "$direction 错将分组顺序当作依赖")
            }
            val again = layoutUnderstandingClusters(graph, groups.reversed(), direction = direction)
            assertEquals(layout.nodes.mapValues { it.value.bounds }, again.nodes.mapValues { it.value.bounds })
            assertEquals(layout.edges.map { it.path.bounds2D }, again.edges.map { it.path.bounds2D })
        }
    }

    @Test
    fun `article publication regression keeps groups compact and all content clear`() {
        val (article, specs) = groupedFlowFixture("article-publish-clusters")
        article.validate()
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(article, specs, direction = direction)
            assertContentClear(layout)
            val branches = layout.clusters.filter { it.id != "submission" }
            val starts = branches.map { if (direction == LayoutDirection.VERTICAL) it.bounds.y else it.bounds.x }
            assertTrue(starts.max() - starts.min() < 100.0, "$direction 独立消费组未对齐: $starts")
            val rank = layout.clusters.single { it.id == "activity" }
            assertTrue(rank.bounds.height < 250, "$direction 活跃积分组被拉长")
        }
    }

    @Test
    fun `fork join parallel relation return edge and self loop retain readable geometry`() {
        val joined = graph.copy(edges = graph.edges + listOf(
            GraphEdge("join-a", "stats-1", "sitemap-1", "data_flow", "完成统计"),
            GraphEdge("join-b", "rank-0", "sitemap-1", "data_flow", "完成积分"),
            GraphEdge("parallel", "main-7", "search-0", "data_flow", "另一路数据"),
            GraphEdge("retry", "sitemap-1", "main-1", "control_flow", "需要重新处理"),
            GraphEdge("self", "search-4", "search-4", "call", "重试"),
        ))
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(joined, groups, direction = direction)
            assertEquals(joined.edges.map { it.id }, layout.edges.map { it.edge.id })
            assertContentClear(layout)
        }
    }

    @Test
    fun `adding a cross group relation preserves local geometry when port capacity is unchanged`() {
        for (direction in LayoutDirection.entries) {
            val separate = layoutUnderstandingClusters(graph.copy(edges = graph.edges.filterNot { it.id.startsWith("dispatch-") }),
                groups, direction = direction)
            val connected = layoutUnderstandingClusters(graph, groups, direction = direction)
            groups.forEach { group ->
                val first = separate.clusters.single { it.id == group.id }.bounds
                val second = connected.clusters.single { it.id == group.id }.bounds
                val resized = group.nodeIds.any { id ->
                    val a = separate.nodes.getValue(id).bounds
                    val b = connected.nodes.getValue(id).bounds
                    a.width != b.width || a.height != b.height
                }
                if (resized) {
                    // Dense faces may grow before layout so external edges keep independent ports.
                    assertTrue(connected.edges.filter { it.edge.from in group.nodeIds && it.edge.to in group.nodeIds }
                        .all { second.contains(it.paintBounds) })
                    return@forEach
                }
                assertEquals(first.width to first.height, second.width to second.height)
                group.nodeIds.forEach { id ->
                    val a = separate.nodes.getValue(id).bounds
                    val b = connected.nodes.getValue(id).bounds
                    assertEquals(a.x - first.x, b.x - second.x, 0.001)
                    assertEquals(a.y - first.y, b.y - second.y, 0.001)
                }
                val a = separate.edges.filter { it.edge.from in group.nodeIds && it.edge.to in group.nodeIds }
                val b = connected.edges.filter { it.edge.from in group.nodeIds && it.edge.to in group.nodeIds }
                a.zip(b).forEach { (before, after) ->
                    assertEquals(before.path.bounds2D.width, after.path.bounds2D.width, 0.001)
                    assertEquals(before.path.bounds2D.height, after.path.bounds2D.height, 0.001)
                }
            }
        }
    }

    @Test
    fun `examples reserve space before arranging adjacent groups`() {
        val panels = groups.take(2).map { DiagramExamplePanel(it.id, "输入", "示例", emptySet(), emptyMap()) }
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(graph, groups, panels, direction)
            assertGeometry(layout)
            assertContentClear(layout)
            layout.clusters.mapNotNull { it.exampleBounds }.forEach { example ->
                assertTrue(layout.nodes.values.none { it.bounds.intersects(example) })
                assertTrue(layout.edges.none { edge -> edge.segments.any { it.crosses(example) } })
            }
        }
    }

    @Test
    fun `paid article flow retains clear dual channels callbacks and state reuse cycle`() {
        val (paid, specs) = groupedFlowFixture("paid-article-clusters")
        paid.validate()
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(paid, specs, direction = direction)
            assertEquals(41, layout.nodes.size)
            assertEquals(50, layout.edges.size)
            assertEquals(8, layout.clusters.size)
            assertContentClear(layout)
        }
    }

    @Test
    fun `paid article ordinary canvas has no group frame and routes clear in both directions`() {
        val (paid, _) = groupedFlowFixture("paid-article-clusters")
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingGraph(paid, direction)
            assertEquals(41, layout.nodes.size)
            assertEquals(50, layout.edges.size)
            assertTrue(layout.clusters.isEmpty())
            assertContentClear(layout)
        }
    }

    private fun assertContentClear(layout: UnderstandingGraphLayout) {
        val scene = EdgeRoutingObstacles(layout.nodes.values, clusterRoutingObstacles(layout.clusters),
            unrelatedClusterObstacles(layout.clusters, layout.edges))
        layout.edges.forEach(scene::add)
        layout.clusters.forEach { cluster ->
            val title = DiagramClusterText.titleBounds(cluster)
            cluster.nodeIds.forEach { nodeId ->
                assertTrue(cluster.bounds.contains(layout.nodes.getValue(nodeId).bounds))
                assertFalse(title.intersects(layout.nodes.getValue(nodeId).bounds))
            }
            layout.edges.filter { it.edge.from in cluster.nodeIds && it.edge.to in cluster.nodeIds }.forEach {
                assertTrue(cluster.bounds.contains(it.paintBounds), "${it.edge.id} 组内线溢出 ${cluster.id}")
            }
        }
        layout.edges.forEach { edge ->
            assertTrue(scene.conflicts(edge).isEmpty(), "${edge.edge.id} 与 ${scene.conflicts(edge)} 冲突")
            assertFalse(edge.resolvedLabelBounds?.intersects(edge.arrowPath.bounds2D) ?: false)
            layout.nodes.values.filter { it.node.id != edge.edge.from && it.node.id != edge.edge.to }.forEach { node ->
                val b = node.bounds
                val interior = Rectangle2D.Double(b.x + 1, b.y + 1, b.width - 2, b.height - 2)
                assertFalse(edge.segments.any { it.crosses(interior) }, "${edge.edge.id} 穿过 ${node.node.id}")
                edge.resolvedLabelBounds?.let { assertFalse(it.intersects(b), "${edge.edge.id} 标签压住节点") }
            }
            layout.clusters.forEach { cluster ->
                assertFalse(edge.segments.any { it.crosses(DiagramClusterText.titleBounds(cluster)) },
                    "${edge.edge.id} 穿过 ${cluster.id} 标题")
                if (edge.edge.from !in cluster.nodeIds && edge.edge.to !in cluster.nodeIds) {
                    val b = cluster.bounds
                    assertFalse(edge.segments.any { it.crosses(Rectangle2D.Double(b.x + 1, b.y + 1,
                        b.width - 2, b.height - 2)) }, "${edge.edge.id} 穿过无关组 ${cluster.id}")
                }
            }
            assertTrue(layout.bounds.contains(edge.paintBounds))
        }
    }

    private fun assertGeometry(layout: UnderstandingGraphLayout) {
        assertEquals(graph.nodes.size, layout.nodes.size)
        assertEquals(graph.edges.size, layout.edges.size)
        layout.clusters.forEach { group ->
            assertTrue(group.bounds.x.isFinite() && group.bounds.y.isFinite())
            group.nodeIds.forEach { assertTrue(group.bounds.contains(layout.nodes.getValue(it).bounds)) }
            layout.clusters.filter { it.id != group.id }.forEach {
                assertFalse(group.bounds.intersects(it.bounds), "${group.id} 和 ${it.id} 重叠")
            }
        }
        layout.edges.forEach { edge ->
            val target = layout.nodes.getValue(edge.edge.to).bounds
            assertTrue(edge.arrowX in target.minX - 1..target.maxX + 1)
            assertTrue(edge.arrowY in target.minY - 1..target.maxY + 1)
            assertTrue(listOf(abs(edge.arrowX - target.minX), abs(edge.arrowX - target.maxX),
                abs(edge.arrowY - target.minY), abs(edge.arrowY - target.maxY)).min() < 1.0)
            assertTrue(layout.bounds.contains(edge.paintBounds))
        }
    }
}
