package com.master.lessplain.diagram

import java.awt.Point
import java.awt.geom.Line2D
import java.awt.geom.Point2D

/** Labels take precedence; a shared connector segment never guesses between parallel candidates. */
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
    return candidateEdgeAt(layout.edges, routeChoice.edgeIds, screenToWorld(point), 6.0 / zoom)
}
