package com.master.lessplain.diagram

import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class DiagramCrossing(val firstEdgeId: String, val secondEdgeId: String, val point: Point2D.Double)

/** 交叉仅指线段内部相交，不包含共用端点或逻辑分支。 */
internal fun diagramCrossings(edges: List<PlacedEdge>): List<DiagramCrossing> = buildList {
    for (firstIndex in edges.indices) {
        val first = edges[firstIndex]
        for (secondIndex in firstIndex + 1 until edges.size) {
            val second = edges[secondIndex]
            if (!first.paintBounds.intersects(second.paintBounds)) continue
            val points = linkedSetOf<Pair<Double, Double>>()
            for (a in first.segments) for (b in second.segments) {
                val horizontal = if (abs(a.y1 - a.y2) < 0.01) a else if (abs(b.y1 - b.y2) < 0.01) b else continue
                val vertical = if (abs(a.x1 - a.x2) < 0.01) a else if (abs(b.x1 - b.x2) < 0.01) b else continue
                if (horizontal === vertical) continue
                val x = vertical.x1
                val y = horizontal.y1
                if (x > min(horizontal.x1, horizontal.x2) + 5 && x < max(horizontal.x1, horizontal.x2) - 5 &&
                    y > min(vertical.y1, vertical.y2) + 5 && y < max(vertical.y1, vertical.y2) - 5) points += x to y
            }
            points.forEach { (x, y) -> add(DiagramCrossing(first.edge.id, second.edge.id, Point2D.Double(x, y))) }
        }
    }
}
