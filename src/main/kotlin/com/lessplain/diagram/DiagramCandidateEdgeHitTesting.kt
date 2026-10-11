package com.lessplain.diagram

import java.awt.Point
import java.awt.geom.Line2D
import java.awt.geom.Point2D

/** 优先命中标签，共用连线段不能替用户猜测平行候选。 */
internal fun candidateEdgeAt(edges: List<PlacedEdge>, ids: Set<String>, point: Point2D, tolerance: Double): String? {
    val candidates = edges.filter { it.edge.id in ids }
    val labels = candidates.filter { it.resolvedLabelBounds?.contains(point) == true }
    if (labels.isNotEmpty()) return labels.singleOrNull()?.edge?.id
    val distances = candidates.mapNotNull { edge ->
        val distance = edge.segments.minOfOrNull {
            Line2D.ptSegDist(it.x1, it.y1, it.x2, it.y2, point.x, point.y)
        } ?: return@mapNotNull null
        (edge.edge.id to distance).takeIf { distance <= tolerance }
    }.sortedBy { it.second }
    val nearest = distances.firstOrNull() ?: return null
    if (distances.size > 1 && kotlin.math.abs(distances[1].second - nearest.second) < 0.001) return null
    return nearest.first
}

internal fun UnderstandingDiagram.candidateEdgeAt(point: Point): String? {
    if (nodeAt(point) != null) return null
    return candidateEdgeAt(layout.edges, routeNavigation?.clickableEdgeIds ?: routeChoice.edgeIds,
        screenToWorld(point), 6.0 / zoom)
}

/** 普通边与分支候选共用几何消歧，但不扩大左键选路范围。 */
internal fun UnderstandingDiagram.edgeAt(point: Point): com.lessplain.model.GraphEdge? {
    if (nodeAt(point) != null || clusterEditAt(point) != null) return null
    val id = candidateEdgeAt(layout.edges, layout.edges.mapTo(hashSetOf()) { it.edge.id },
        screenToWorld(point), 6.0 / zoom) ?: return null
    return record.graph.edges.firstOrNull { it.id == id }
}

internal fun UnderstandingDiagram.edgeTooltipAt(point: Point): String? = edgeAt(point)?.let {
    edgeTooltip(it, record.graph, getFontMetrics(javax.swing.UIManager.getFont("ToolTip.font") ?: font))
}
