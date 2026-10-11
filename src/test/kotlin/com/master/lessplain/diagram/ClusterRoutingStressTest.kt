package com.master.lessplain.diagram

import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** 生成拓扑用于防止只针对两个已保存业务样例调优。 */
class ClusterRoutingStressTest {
    @Test fun `unseen multi entry mixed relations joins parallel edges and cycles keep independent lanes`() {
        val failures = mutableListOf<String>()
        for (seed in 0..6) for (direction in LayoutDirection.entries) {
            val (graph, clusters) = if (seed == 6) generatedGraph(seed, 8, 10, 24) else generatedGraph(seed)
            val layout = layoutUnderstandingClusters(graph, clusters, direction = direction)
            assertEquals(graph.edges.map { it.id }, layout.edges.map { it.edge.id })
            val defects = clusterRouteDefects(layout)
            if (defects != 0) {
                val scene = EdgeRoutingObstacles(layout.nodes.values, clusterRoutingObstacles(layout.clusters),
                    unrelatedClusterObstacles(layout.clusters, layout.edges), DIAGRAM_LANE_GAP, 0.01,
                    clusterBorderBounds(layout.clusters, layout.edges))
                layout.edges.forEach(scene::add)
                failures += "$seed/$direction: " + layout.edges.filter {
                    scene.conflicts(it).isNotEmpty() || scene.crossesNode(it) || scene.labelCrossesNode(it)
                }.joinToString { edge -> "${edge.edge}(${scene.conflicts(edge)}) " +
                    layout.edges.filter { it.edge.id in scene.conflicts(edge) }.flatMap { other ->
                        edge.segments.flatMap { a -> other.segments.filter { a.sharedRun(it, DIAGRAM_LANE_GAP) > 0.01 }.map { b -> "$a / $b" } }
                    }.joinToString() }
            }
        }
        assertEquals(emptyList(), failures)
    }

    private fun generatedGraph(seed: Int, groupCount: Int = 5, nodeCount: Int = 6,
        crossCount: Int = 12): Pair<UnderstandingGraph, List<DiagramCluster>> {
        val random = Random(seed)
        val nodes = (0 until groupCount).flatMap { group -> (0 until nodeCount).map { GraphNode("$group-$it", "阶段 $group / 步骤 $it") } }
        val edges = (0 until groupCount).flatMap { group -> (0 until nodeCount - 1).map {
            GraphEdge("local-$group-$it", "$group-$it", "$group-${it + 1}", "call")
        } }.toMutableList()
        repeat(crossCount) { index ->
            val fromGroup = random.nextInt(groupCount - 1)
            val toGroup = random.nextInt(fromGroup + 1, groupCount)
            val from = "$fromGroup-${random.nextInt(1, nodeCount)}"
            val to = "$toGroup-${random.nextInt(nodeCount - 1)}"
            edges += GraphEdge("cross-$index", from, to, if (index % 2 == 0) "call" else "data_flow")
            if (index % 4 == 0) edges += GraphEdge("parallel-$index", from, to, "data_flow")
        }
        edges += GraphEdge("retry", "${groupCount - 1}-${nodeCount - 1}", "0-1", "call")
        edges += GraphEdge("self", "2-3", "2-3", "call")
        val groups = (0 until groupCount).map { group -> DiagramCluster("group-$group", "职责阶段 $group",
            (0 until nodeCount).mapTo(linkedSetOf()) { "$group-$it" }) }
        return UnderstandingGraph(nodes, edges) to groups
    }
}
