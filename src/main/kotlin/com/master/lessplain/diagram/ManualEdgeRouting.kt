package com.master.lessplain.diagram

/** 拖动保留节点坐标，只重新布置关联或新近受阻的局部连线。 */
internal fun routeMovedEdges(
    base: UnderstandingGraphLayout,
    nodes: Map<String, PlacedNode>,
    mainlineIds: Set<String>,
): List<PlacedEdge> {
    val movedIds = base.edges.filter { edge ->
        nodes.getValue(edge.edge.from).bounds != base.nodes.getValue(edge.edge.from).bounds ||
            nodes.getValue(edge.edge.to).bounds != base.nodes.getValue(edge.edge.to).bounds
    }.mapTo(hashSetOf()) { it.edge.id }
    return repairEdgeRoutes(nodes, base.edges, mainlineIds, movedIds, clusterRoutingObstacles(base.clusters))
}
