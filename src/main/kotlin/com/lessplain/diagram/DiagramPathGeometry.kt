package com.lessplain.diagram

import java.awt.geom.Path2D
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** 共用圆角正交路径，使手动修复与 ELK 输出保持一致。 */
internal fun roundedEdgePath(raw: List<Point2D.Double>): Path2D.Double {
    val points = mutableListOf<Point2D.Double>()
    raw.forEach { point ->
        if (points.lastOrNull()?.distance(point)?.let { it < 0.01 } == true) return@forEach
        while (points.size >= 2) {
            val a = points[points.lastIndex - 1]
            val b = points.last()
            val cross = (b.x - a.x) * (point.y - b.y) - (b.y - a.y) * (point.x - b.x)
            val dot = (b.x - a.x) * (point.x - b.x) + (b.y - a.y) * (point.y - b.y)
            if (abs(cross) >= 0.01 || dot < 0) break
            points.removeAt(points.lastIndex)
        }
        points.add(point)
    }
    return Path2D.Double().apply {
        if (points.isEmpty()) return@apply
        moveTo(points.first().x, points.first().y)
        for (index in 1 until points.lastIndex) {
            val before = points[index - 1]
            val corner = points[index]
            val after = points[index + 1]
            val incoming = hypot(corner.x - before.x, corner.y - before.y)
            val outgoing = hypot(after.x - corner.x, after.y - corner.y)
            val radius = min(7.0, min(incoming, outgoing) / 2)
            lineTo(corner.x - (corner.x - before.x) / incoming * radius,
                corner.y - (corner.y - before.y) / incoming * radius)
            quadTo(corner.x, corner.y, corner.x + (after.x - corner.x) / outgoing * radius,
                corner.y + (after.y - corner.y) / outgoing * radius)
        }
        lineTo(points.last().x, points.last().y)
    }
}
