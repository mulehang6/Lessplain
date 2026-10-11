package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.UnderstandingGraph
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.abs
import kotlin.math.max

internal enum class ClusterRoutingVersion { LEGACY, SEPARATE_LANES }

internal enum class RoutingSide(val dx: Int, val dy: Int) {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
    fun opposite(): RoutingSide = entries[(ordinal + 2) % 4]
}

internal data class DiagramPort(val point: Point2D.Double, val side: RoutingSide)
internal data class DiagramEdgePorts(val source: DiagramPort, val target: DiagramPort)

internal const val DIAGRAM_LANE_GAP = 12.0

/** 首轮全局布线后撤销冲突预留，使先处理的边也能让出通道。 */
internal fun refineClusterLanes(
    nodes: Map<String, PlacedNode>,
    edges: List<PlacedEdge>,
    clusters: List<PlacedCluster>,
    ports: Map<String, DiagramEdgePorts>,
): List<PlacedEdge> {
    val extras = clusterRoutingObstacles(clusters)
    val forbidden = unrelatedClusterObstacles(clusters, edges)
    val scene = EdgeRoutingObstacles(nodes.values, extras, forbidden, DIAGRAM_LANE_GAP, 0.01,
        clusterBorderBounds(clusters, edges))
    val current = edges.associateByTo(linkedMapOf()) { it.edge.id }
    edges.forEach(scene::add)
    val router = LocalEdgeRouter(nodes, edges.sortedBy { it.edge.id }, extras, forbidden, ports)
    val order = ports.keys.sorted()
    repeat(3) { pass ->
        var changed = false
        for (id in if (pass % 2 == 0) order else order.reversed()) {
            val original = current.getValue(id)
            if (scene.collisionCost(original) == 0.0) continue
            val before = scene.collisionCost(original)
            scene.remove(id)
            val replacement = router.route(original, scene, moved = true)
            if (scene.collisionCost(replacement) < before) {
                current[id] = replacement
                changed = true
            }
            scene.add(current.getValue(id))
        }
        if (!changed) return edges.map { current.getValue(it.edge.id) }
    }
    return edges.map { current.getValue(it.edge.id) }
}

/** 同时释放冲突边再交换布线顺序，避免两条线互相占住唯一的局部通道。 */
internal fun rerouteClusterConflicts(
    nodes: Map<String, PlacedNode>,
    edges: List<PlacedEdge>,
    clusters: List<PlacedCluster>,
    ports: Map<String, DiagramEdgePorts>,
): List<PlacedEdge> {
    fun scene() = EdgeRoutingObstacles(nodes.values, clusterRoutingObstacles(clusters),
        unrelatedClusterObstacles(clusters, edges), DIAGRAM_LANE_GAP, 0.01, clusterBorderBounds(clusters, edges))
    val originalScene = scene().apply { edges.forEach(::add) }
    val affected = edges.filter { it.edge.id in ports && originalScene.collisionCost(it) > 0 }.map { it.edge.id }.sorted()
    if (affected.isEmpty()) return edges
    var best = edges
    var bestCost = edges.sumOf(originalScene::collisionCost)
    val byId = edges.associateBy { it.edge.id }
    val router = LocalEdgeRouter(nodes, edges, clusterRoutingObstacles(clusters),
        unrelatedClusterObstacles(clusters, edges), ports)
    for (order in listOf(affected.reversed(), affected)) {
        val obstacles = scene().apply { edges.filter { it.edge.id !in affected }.forEach(::add) }
        val replacements = mutableMapOf<String, PlacedEdge>()
        order.forEach { id ->
            val replacement = router.route(byId.getValue(id), obstacles, moved = true)
            replacements[id] = replacement
            obstacles.add(replacement)
        }
        val candidate = edges.map { replacements[it.edge.id] ?: it }
        val cost = candidate.sumOf(obstacles::collisionCost)
        if (cost < bestCost) {
            best = candidate
            bestCost = cost
        }
    }
    return best
}

/** 局部布局前预留容量，避免密集节点将独立端口挤在一起。 */
internal fun reserveDiagramPorts(input: DiagramLayoutInput, fullGraph: UnderstandingGraph): DiagramLayoutInput {
    val outgoing = fullGraph.edges.groupingBy { it.from }.eachCount()
    val incoming = fullGraph.edges.groupingBy { it.to }.eachCount()
    return input.copy(nodeSizes = input.nodeSizes.mapValues { (id, dimensions) ->
        val capacity = max(outgoing[id] ?: 0, incoming[id] ?: 0)
        val length = 24.0 + capacity * DIAGRAM_LANE_GAP * 2
        if (input.direction == LayoutDirection.VERTICAL) max(dimensions.first, length) to dimensions.second
        else dimensions.first to max(dimensions.second, length)
    })
}

/** 按另一端端点的顺序统一分配外部端口，先预留已有局部端口，同一侧的新端口间隔至少十二个逻辑像素。 */
internal fun allocateClusterPorts(
    nodes: Map<String, PlacedNode>,
    insideEdges: Collection<PlacedEdge>,
    outsideEdges: List<GraphEdge>,
    direction: LayoutDirection,
): Map<String, DiagramEdgePorts> {
    val sourceSide = if (direction == LayoutDirection.VERTICAL) RoutingSide.SOUTH else RoutingSide.EAST
    val targetSide = sourceSide.opposite()
    val reservations = mutableMapOf<Pair<String, RoutingSide>, MutableList<Double>>()
    fun coordinate(point: Point2D.Double, side: RoutingSide): Double = if (side.dx == 0) point.x else point.y
    fun side(point: Point2D.Double, bounds: Rectangle2D): RoutingSide = RoutingSide.entries.minBy {
        when (it) {
            RoutingSide.NORTH -> abs(point.y - bounds.minY)
            RoutingSide.SOUTH -> abs(point.y - bounds.maxY)
            RoutingSide.WEST -> abs(point.x - bounds.minX)
            RoutingSide.EAST -> abs(point.x - bounds.maxX)
        }
    }
    fun reserve(nodeId: String, point: Point2D.Double) {
        val face = side(point, nodes.getValue(nodeId).bounds)
        reservations.getOrPut(nodeId to face) { mutableListOf() }.add(coordinate(point, face))
    }
    insideEdges.forEach { edge ->
        edge.segments.firstOrNull()?.let { reserve(edge.edge.from, Point2D.Double(it.x1, it.y1)) }
        reserve(edge.edge.to, Point2D.Double(edge.arrowX, edge.arrowY))
    }
    fun assign(edges: List<GraphEdge>, source: Boolean): Map<String, DiagramPort> = buildMap {
        val face = if (source) sourceSide else targetSide
        edges.groupBy { if (source) it.from else it.to }.toSortedMap().forEach { (nodeId, group) ->
            val box = nodes.getValue(nodeId).bounds
            val min = if (face.dx == 0) box.minX else box.minY
            val max = if (face.dx == 0) box.maxX else box.maxY
            val occupied = reservations.getOrPut(nodeId to face) { mutableListOf() }
            val ordered = group.sortedWith(compareBy<GraphEdge> {
                val opposite = nodes.getValue(if (source) it.to else it.from).bounds
                if (face.dx == 0) opposite.centerX else opposite.centerY
            }.thenBy { it.id })
            val slots = generateSequence(min + 12) { it + DIAGRAM_LANE_GAP }
                .takeWhile { it <= max - 12 + 0.01 }.toList()
            val free = slots.filter { value -> occupied.none { abs(it - value) < DIAGRAM_LANE_GAP - 0.01 } }
            require(free.size >= ordered.size) { "节点 $nodeId 的独立端口空间不足" }
            val centered = free.sortedBy { abs(it - (min + max) / 2) }.take(ordered.size).sorted()
            ordered.zip(centered).forEach { (edge, position) ->
                val point = if (face.dx == 0) Point2D.Double(position, if (face.dy < 0) box.minY else box.maxY)
                    else Point2D.Double(if (face.dx < 0) box.minX else box.maxX, position)
                put(edge.id, DiagramPort(point, face))
                occupied += position
            }
        }
    }
    val sources = assign(outsideEdges, source = true)
    val targets = assign(outsideEdges, source = false)
    return outsideEdges.associate { it.id to DiagramEdgePorts(sources.getValue(it.id), targets.getValue(it.id)) }
}

/** 统计未解决的内容和通道缺陷，不把孤立交叉当作失败。 */
internal fun clusterRouteDefects(layout: UnderstandingGraphLayout): Int {
    val scene = EdgeRoutingObstacles(layout.nodes.values, clusterRoutingObstacles(layout.clusters),
        unrelatedClusterObstacles(layout.clusters, layout.edges), DIAGRAM_LANE_GAP, 0.01,
        clusterBorderBounds(layout.clusters, layout.edges))
    layout.edges.forEach(scene::add)
    return layout.edges.sumOf {
        scene.conflicts(it).size + (if (scene.crossesNode(it)) 1 else 0) + (if (scene.labelCrossesNode(it)) 1 else 0)
    }
}
