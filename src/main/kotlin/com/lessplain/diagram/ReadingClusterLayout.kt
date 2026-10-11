package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import java.awt.geom.Point2D

/** 分组位置只服从阅读阶段；全部关系在位置固定后布线，回边不拉散阶段。 */
internal object ReadingClusterLayout {
    fun layout(
        graph: UnderstandingGraph,
        specs: List<DiagramCluster>,
        examples: List<DiagramExamplePanel>,
        direction: LayoutDirection,
    ): UnderstandingGraphLayout {
        val covered = specs.flatMapTo(hashSetOf()) { it.nodeIds }
        val ungrouped = graph.nodes.mapTo(linkedSetOf()) { it.id }.apply { removeAll(covered) }
        val groups = if (ungrouped.isEmpty()) specs else specs + DiagramCluster("@ungrouped", "其他步骤", ungrouped)
        val contents = groups.associate { group ->
            group.id to layoutClusterContent(graph, group, examples.firstOrNull { it.clusterId == group.id }, direction)
        }
        val owner = groups.flatMap { group -> group.nodeIds.map { it to group.id } }.toMap()
        val phases = readingClusterPhases(graph, groups)
        val external = graph.edges.filter { owner[it.from] != owner[it.to] }
        var best: UnderstandingGraphLayout? = null
        var bestDefects = Int.MAX_VALUE
        for (gap in listOf(112.0, 160.0, 208.0, 256.0)) {
            val positions = clusterStagePositions(groups, contents, phases, direction, gap)
            val placed = groups.map { group ->
                val point = positions.getValue(group.id)
                contents.getValue(group.id).translated(point.x, point.y)
            }
            val byId = placed.flatMap { it.nodes.entries }.associate { it.key to it.value }
            val nodes = graph.nodes.associateTo(linkedMapOf()) { it.id to byId.getValue(it.id) }
            val internal = placed.flatMap { it.edges }.associateBy { it.edge.id }
            val ports = allocateClusterPorts(nodes, internal.values, external, direction)
            val edges = graph.edges.map { edge ->
                internal[edge.id] ?: initialClusterEdge(edge, ports.getValue(edge.id))
            }
            val clusters = placed.map { it.cluster }
            val routed = repairEdgeRoutes(nodes, edges, mainlineEdgeIds(graph),
                movedEdgeIds = external.mapTo(hashSetOf()) { it.id },
                obstacles = clusterRoutingObstacles(clusters),
                edgeObstacles = unrelatedClusterObstacles(clusters, edges),
                stableEdgeIds = internal.keys, laneGap = DIAGRAM_LANE_GAP, minimumSharedRun = 0.01,
                fixedPorts = ports, clusterBorders = clusterBorderBounds(clusters, edges))
            val refined = rerouteClusterConflicts(nodes, refineClusterLanes(nodes, routed, clusters, ports), clusters, ports)
            val bounds = graphBounds(nodes.values, refined)
            clusters.forEach { bounds.add(it.bounds) }
            val result = UnderstandingGraphLayout(nodes, refined, bounds, clusters)
            val defects = clusterRouteDefects(result)
            if (defects < bestDefects || defects == bestDefects && bounds.width * bounds.height <
                requireNotNull(best).bounds.width * requireNotNull(best).bounds.height) {
                best = result
                bestDefects = defects
            }
            if (defects == 0) return result
        }
        return requireNotNull(best)
    }
}

/** 先压缩为分组关系，再去除分组级回边，避免组内交错节点制造阶段环。 */
internal fun readingClusterPhases(graph: UnderstandingGraph, groups: List<DiagramCluster>): Map<String, Int> {
    val owner = groups.flatMap { group -> group.nodeIds.map { it to group.id } }.toMap()
    val nodeOrder = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()
    val ordered = groups.sortedWith(compareBy<DiagramCluster> {
        it.nodeIds.minOfOrNull { id -> nodeOrder[id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
    }.thenBy { it.id })
    val cross = forwardReadingEdges(graph).mapNotNull { edge ->
        val from = owner[edge.from] ?: return@mapNotNull null
        val to = owner[edge.to] ?: return@mapNotNull null
        if (from == to) null else edge.copy(from = from, to = to)
    }
    if (cross.isEmpty()) return groups.mapIndexed { i, group -> group.id to i }.toMap()
    val forward = forwardReadingEdges(UnderstandingGraph(ordered.map { GraphNode(it.id, it.title) }, cross))
    val incoming = forward.groupBy { it.to }
    val phases = mutableMapOf<String, Int>()
    fun phase(id: String): Int = phases.getOrPut(id) {
        incoming[id].orEmpty().maxOfOrNull { phase(it.from) + 1 } ?: 0
    }
    ordered.forEach { phase(it.id) }
    return phases
}

/** 同阶段组共用主轴起点，副轴居中排列，方向切换只交换两个轴。 */
private fun clusterStagePositions(
    groups: List<DiagramCluster>,
    contents: Map<String, DiagramClusterContent>,
    phases: Map<String, Int>,
    direction: LayoutDirection,
    gap: Double,
): Map<String, Point2D.Double> {
    val stages = groups.groupBy { phases.getValue(it.id) }.toSortedMap()
    fun main(id: String) = contents.getValue(id).cluster.bounds.let {
        if (direction == LayoutDirection.VERTICAL) it.height else it.width
    }
    fun cross(id: String) = contents.getValue(id).cluster.bounds.let {
        if (direction == LayoutDirection.VERTICAL) it.width else it.height
    }
    val widths = stages.mapValues { (_, list) -> list.sumOf { cross(it.id) } + gap * (list.size - 1) }
    val maximum = widths.values.maxOrNull() ?: 0.0
    var mainOffset = 32.0
    return buildMap {
        stages.forEach { (phase, list) ->
            var crossOffset = 32.0 + (maximum - widths.getValue(phase)) / 2
            // 阶段内保持语义输入顺序，保证相同图的布局稳定。
            list.forEach { group ->
                put(group.id, if (direction == LayoutDirection.VERTICAL) Point2D.Double(crossOffset, mainOffset)
                    else Point2D.Double(mainOffset, crossOffset))
                crossOffset += cross(group.id) + gap
            }
            mainOffset += list.maxOf { main(it.id) } + gap
        }
    }
}

private fun initialClusterEdge(edge: GraphEdge, ports: DiagramEdgePorts): PlacedEdge {
    val start = ports.source.point
    val end = ports.target.point
    val middle = Point2D.Double(start.x, end.y)
    return PlacedEdge(edge, roundedEdgePath(listOf(start, middle, end)),
        end.x, end.y, end.x - middle.x, end.y - middle.y,
        (start.x + end.x) / 2, (start.y + end.y) / 2)
}
