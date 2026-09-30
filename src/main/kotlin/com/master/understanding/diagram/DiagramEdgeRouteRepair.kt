package com.master.understanding.diagram

import java.awt.geom.Rectangle2D

/** Repairs only conflicting connectors, protecting the stable mainline before other edge IDs. */
internal fun repairEdgeRoutes(
    nodes: Map<String, PlacedNode>,
    edges: List<PlacedEdge>,
    mainlineIds: Set<String>,
    movedEdgeIds: Set<String> = emptySet(),
    obstacles: List<Rectangle2D> = emptyList(),
): List<PlacedEdge> {
    if (edges.isEmpty()) return edges
    val ordered = edges.sortedWith(compareBy<PlacedEdge> { it.edge.id !in mainlineIds }.thenBy { it.edge.id })
    val rank = ordered.mapIndexed { index, edge -> edge.edge.id to index }.toMap()
    val scene = EdgeRoutingObstacles(nodes.values, obstacles)
    edges.forEach(scene::add)
    val affected = movedEdgeIds.toMutableSet()
    ordered.forEach { edge ->
        if (scene.crossesNode(edge) || scene.labelCrossesNode(edge) ||
            edge.resolvedLabelBounds?.intersects(edge.arrowPath.bounds2D) == true) affected.add(edge.edge.id)
        scene.conflicts(edge).forEach { other ->
            affected.add(if (rank.getValue(edge.edge.id) > rank.getValue(other)) edge.edge.id else other)
        }
    }
    if (affected.isEmpty()) return edges
    affected.forEach(scene::remove)
    val router = LocalEdgeRouter(nodes, ordered, obstacles)
    val repaired = hashMapOf<String, PlacedEdge>()
    val byId = ordered.associateBy { it.edge.id }
    val pending = java.util.TreeSet<String>(compareBy { rank.getValue(it) }).apply { addAll(affected) }
    while (pending.isNotEmpty()) {
        val id = requireNotNull(pending.pollFirst())
        val edge = byId.getValue(id)
        val result = router.route(edge, scene, edge.edge.id in movedEdgeIds)
        // A forced route may need a neighbouring connector to yield its lane; expand only that local set.
        scene.conflicts(result).filter { it !in affected }.forEach { other ->
            affected.add(other)
            scene.remove(other)
            pending.add(other)
        }
        repaired[edge.edge.id] = result
        scene.add(result)
    }
    return edges.map { repaired[it.edge.id] ?: it }
}

/** Group title and example regions are content obstacles, while group borders remain traversable. */
internal fun clusterRoutingObstacles(clusters: List<PlacedCluster>): List<Rectangle2D> = clusters.flatMap {
    listOfNotNull(DiagramClusterText.titleBounds(it), it.exampleBounds)
}
