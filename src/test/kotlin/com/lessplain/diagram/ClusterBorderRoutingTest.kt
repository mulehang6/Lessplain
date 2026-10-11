package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 复现用户截图中的保存回到呈现、追问回到交接两条跨组回边。 */
class ClusterBorderRoutingTest {
    @Test
    fun `real agent approval recovery and event loops keep eight cluster routes clear`() {
        val (agent, groups) = groupedFlowFixture("mulehang-agent-run-clusters")
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(agent, groups, direction = direction)
            assertEquals(39, layout.nodes.size)
            assertEquals(66, layout.edges.size)
            assertEquals(8, layout.clusters.size)
            assertEquals(agent.edges.map { it.id }, layout.edges.map { it.edge.id })
            val phases = readingClusterPhases(agent, groups)
            layout.clusters.groupBy { phases.getValue(it.id) }.values.forEach { stage ->
                val starts = stage.map { if (direction == LayoutDirection.VERTICAL) it.bounds.y else it.bounds.x }
                assertTrue(starts.max() - starts.min() < 0.001, "同阶段分组必须对齐")
            }
            val boxes = layout.clusters.groupBy { phases.getValue(it.id) }.toSortedMap().values.toList()
            boxes.zipWithNext().forEach { (before, after) ->
                val end = before.maxOf { if (direction == LayoutDirection.VERTICAL) it.bounds.maxY else it.bounds.maxX }
                val start = after.minOf { if (direction == LayoutDirection.VERTICAL) it.bounds.y else it.bounds.x }
                assertTrue(start > end, "串行阶段不可倒置或重叠")
            }
            if (direction == LayoutDirection.VERTICAL) {
                assertTrue(layout.bounds.width < 3300 && layout.bounds.height < 4700, "纵向跨度超过阅读预算")
            } else {
                assertTrue(layout.bounds.width < 8200 && layout.bounds.height < 2100, "横向跨度超过阅读预算")
            }
            val scene = EdgeRoutingObstacles(layout.nodes.values, clusterRoutingObstacles(layout.clusters),
                unrelatedClusterObstacles(layout.clusters, layout.edges), DIAGRAM_LANE_GAP, 0.01,
                clusterBorderBounds(layout.clusters, layout.edges))
            layout.edges.forEach(scene::add)
            val problems = layout.edges.filter { scene.collisionCost(it) > 0 }.map {
                "${it.edge.id}: conflicts=${scene.conflicts(it)}, content=${scene.crossesNode(it)}, label=${scene.labelCrossesNode(it)}"
            }
            // 保留布局产物供后续比较，不通过启动 IDE 获取截图。
            val output = java.nio.file.Path.of("build/reports/cluster-routing")
            java.nio.file.Files.createDirectories(output)
            RoutingArtifacts.writeSvg(layout, output.resolve("mulehang-agent-${direction.name.lowercase()}.svg"))
            assertEquals(0, clusterRouteDefects(layout), "$direction Agent 审批和恢复流程仍有通道缺陷: $problems")
        }
    }

    @Test
    fun `return routes stay away from endpoint cluster borders in both directions`() {
        for (direction in LayoutDirection.entries) {
            val layout = layoutUnderstandingClusters(graph, clusters, direction = direction)
            assertEquals(graph.edges.map { it.id }, layout.edges.map { it.edge.id })
            for (edge in layout.edges.filter { it.edge.id in setOf("persist-view", "note-context") }) {
                for (cluster in layout.clusters) {
                    val box = cluster.bounds
                    val borders = listOf(
                        RoutingSegment(box.minX, box.minY, box.maxX, box.minY),
                        RoutingSegment(box.minX, box.maxY, box.maxX, box.maxY),
                        RoutingSegment(box.minX, box.minY, box.minX, box.maxY),
                        RoutingSegment(box.maxX, box.minY, box.maxX, box.maxY),
                    )
                    assertTrue(edge.segments.none { segment ->
                        borders.any { segment.sharedRun(it, DIAGRAM_LANE_GAP) > 8.0 }
                    }, "$direction ${edge.edge.id} 沿 ${cluster.id} 的边框贴边")
                }
                val target = layout.nodes.getValue(edge.edge.to).bounds
                assertTrue(target.intersects(Rectangle2D.Double(edge.arrowX - 0.5, edge.arrowY - 0.5, 1.0, 1.0)))
            }
            assertEquals(0, clusterRouteDefects(layout), "$direction 仍有通道冲突")
        }
    }

    private val graph = UnderstandingGraph(
        listOf(
        GraphNode("context", "向 Agent 提供来源\n返回图与待答问题"),
        GraphNode("submit", "接收 Agent 理解图\n解析协议与归属"),
        GraphNode("request", "成功重试直接重放\n新请求准备后入事务"),
        GraphNode("validate", "校验当前文档锚点\n包括未保存编辑"),
        GraphNode("persist", "保存理解记录\n移除对应待答注释"),
        GraphNode("view", "Compose 嵌入 Swing 图\n绑定阅读与追问"),
        GraphNode("navigate", "定位源码与提示\n片段变化暂停旧 inline"),
        GraphNode("note", "保存节点追问\n固定来源等 Agent 回答"),
        ),
        listOf(
        GraphEdge("context-submit", "context", "submit", "data_flow"),
        GraphEdge("submit-request", "submit", "request", "call"),
        GraphEdge("request-validate", "request", "validate", "call"),
        GraphEdge("request-persist", "request", "persist", "call"),
        GraphEdge("validate-persist", "validate", "persist", "data_flow"),
        GraphEdge("persist-view", "persist", "view", "data_flow"),
        GraphEdge("view-navigate", "view", "navigate", "control_flow", "用户点击节点或沿路径阅读"),
        GraphEdge("view-note", "view", "note", "control_flow", "用户提交节点追问"),
        GraphEdge("note-context", "note", "context", "data_flow"),
        ),
    )
    private val clusters = listOf(
        DiagramCluster("agent-save", "Agent 交接与校验保存", setOf("context", "submit", "request", "validate", "persist")),
        DiagramCluster("view-source-note", "呈现、源码与节点追问", setOf("view", "navigate", "note")),
    )
}
