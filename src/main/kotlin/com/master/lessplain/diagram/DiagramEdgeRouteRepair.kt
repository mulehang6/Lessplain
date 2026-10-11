package com.master.lessplain.diagram

import java.awt.geom.Rectangle2D

/** 修复冲突连线；主路径布线前，可保留已验证的组内边。 */
internal fun repairEdgeRoutes(
    nodes: Map<String, PlacedNode>,
    edges: List<PlacedEdge>,
    mainlineIds: Set<String>,
    movedEdgeIds: Set<String> = emptySet(),
    obstacles: List<Rectangle2D> = emptyList(),
    edgeObstacles: Map<String, List<Rectangle2D>> = emptyMap(),
    stableEdgeIds: Set<String> = emptySet(),
    laneGap: Double = 8.0,
    minimumSharedRun: Double = 12.0,
    fixedPorts: Map<String, DiagramEdgePorts> = emptyMap(),
    clusterBorders: Map<String, List<Rectangle2D>> = emptyMap(),
): List<PlacedEdge> {
    if (edges.isEmpty()) return edges
    val ordered = edges.sortedWith(compareBy<PlacedEdge> { it.edge.id !in stableEdgeIds }
        .thenBy { it.edge.id !in mainlineIds }.thenBy { it.edge.id })
    val rank = ordered.mapIndexed { index, edge -> edge.edge.id to index }.toMap()
    val scene = EdgeRoutingObstacles(nodes.values, obstacles, edgeObstacles, laneGap, minimumSharedRun, clusterBorders)
    edges.forEach(scene::add)
    val affected = movedEdgeIds.toMutableSet()
    ordered.forEach { edge ->
        if (edge.edge.id in stableEdgeIds) return@forEach
        if (scene.crossesNode(edge) || scene.labelCrossesNode(edge) ||
            edge.resolvedLabelBounds?.intersects(edge.arrowPath.bounds2D) == true) affected.add(edge.edge.id)
        scene.conflicts(edge).forEach { other ->
            affected.add(if (rank.getValue(edge.edge.id) > rank.getValue(other)) edge.edge.id else other)
        }
    }
    if (affected.isEmpty()) return edges
    affected.forEach(scene::remove)
    val router = LocalEdgeRouter(nodes, ordered, obstacles, edgeObstacles, fixedPorts)
    val repaired = hashMapOf<String, PlacedEdge>()
    val byId = ordered.associateBy { it.edge.id }
    val pending = java.util.TreeSet<String>(compareBy { rank.getValue(it) }).apply { addAll(affected) }
    while (pending.isNotEmpty()) {
        val id = requireNotNull(pending.pollFirst())
        val edge = byId.getValue(id)
        val result = router.route(edge, scene, edge.edge.id in movedEdgeIds)
        // 强制路线可能要求邻近连线让出通道，仅扩大对应的局部集合。
        scene.conflicts(result).filter { it !in affected && it !in stableEdgeIds }.forEach { other ->
            affected.add(other)
            scene.remove(other)
            pending.add(other)
        }
        repaired[edge.edge.id] = result
        scene.add(result)
    }
    return edges.map { repaired[it.edge.id] ?: it }
}

/** 分组标题和示例区域是内容障碍，分组边框仍可穿过。 */
internal fun clusterRoutingObstacles(clusters: List<PlacedCluster>): List<Rectangle2D> = clusters.flatMap {
    listOfNotNull(DiagramClusterText.titleBounds(it), it.exampleBounds)
}

/** 连线可进入自身端点所属的分组，但必须绕开其他分组。 */
internal fun unrelatedClusterObstacles(clusters: List<PlacedCluster>, edges: List<PlacedEdge>): Map<String, List<Rectangle2D>> =
    edges.associate { edge -> edge.edge.id to clusters.filter {
        edge.edge.from !in it.nodeIds && edge.edge.to !in it.nodeIds
    }.map { cluster -> cluster.bounds.let { Rectangle2D.Double(it.x + 1, it.y + 1, it.width - 2, it.height - 2) } } }
