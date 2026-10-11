package com.lessplain.diagram

import java.awt.geom.Rectangle2D

/** 跨组线可垂直穿过边框，但平行段必须留出独立通道，避免与组边界混为一条线。 */
internal class ClusterBorderClearance(
    private val boundsByEdge: Map<String, List<Rectangle2D>>,
) {
    private val segmentsByEdge = boundsByEdge.mapValues { (_, boxes) -> boxes.flatMap(::borders) }

    fun bounds(edgeId: String): List<Rectangle2D> = boundsByEdge[edgeId].orEmpty()

    fun blocks(segment: RoutingSegment, edgeId: String): Boolean = segmentsByEdge[edgeId].orEmpty()
        .any { segment.sharedRun(it, DIAGRAM_LANE_GAP) > 8.0 }

    /** 标签同样不能盖住边框；穿组线本身仍可从标签两侧经过。 */
    fun blocksLabel(label: Rectangle2D, edgeId: String): Boolean = segmentsByEdge[edgeId].orEmpty()
        .any { it.crosses(label) }

    private fun borders(box: Rectangle2D): List<RoutingSegment> = listOf(
        RoutingSegment(box.minX, box.minY, box.maxX, box.minY),
        RoutingSegment(box.minX, box.maxY, box.maxX, box.maxY),
        RoutingSegment(box.minX, box.minY, box.minX, box.maxY),
        RoutingSegment(box.maxX, box.minY, box.maxX, box.maxY),
    )
}

/** 仅跨组边受边框约束，组内布局及其端口保持不变。 */
internal fun clusterBorderBounds(
    clusters: List<PlacedCluster>,
    edges: List<PlacedEdge>,
): Map<String, List<Rectangle2D>> {
    val owner = clusters.flatMap { cluster -> cluster.nodeIds.map { it to cluster.id } }.toMap()
    return edges.filter { owner[it.edge.from] != owner[it.edge.to] }
        .associate { it.edge.id to clusters.map { cluster -> cluster.bounds } }
}
