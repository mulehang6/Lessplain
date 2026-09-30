package com.master.understanding.diagram

import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The arrow geometry is shared by painting, clipping and local routing reservations. */
internal fun edgeArrowPath(edge: PlacedEdge): Path2D.Double {
    val angle = atan2(edge.arrowDy, edge.arrowDx)
    return Path2D.Double().apply {
        moveTo(edge.arrowX, edge.arrowY)
        lineTo(edge.arrowX - 11 * cos(angle - 0.45), edge.arrowY - 11 * sin(angle - 0.45))
        moveTo(edge.arrowX, edge.arrowY)
        lineTo(edge.arrowX - 11 * cos(angle + 0.45), edge.arrowY - 11 * sin(angle + 0.45))
    }
}

/** Legacy paths without ELK label bounds use the same measured font as the painter. */
internal fun edgeLabelBounds(edge: PlacedEdge): Rectangle2D.Double? {
    val label = edge.edge.condition?.takeIf(String::isNotBlank) ?: return null
    edge.labelBounds?.let { return it }
    val (width, height) = DiagramTextMetrics.edgeLabelSize(label)
    return Rectangle2D.Double(edge.labelX - width / 2, edge.labelY - height / 2, width, height)
}

/** Non-degenerate bounds include the largest animated stroke and an antialiasing margin. */
internal fun edgePaintBounds(edge: PlacedEdge): Rectangle2D.Double {
    val bounds = Rectangle2D.Double().apply { setRect(edge.path.bounds2D) }
    bounds.add(edge.arrowPath.bounds2D)
    edge.resolvedLabelBounds?.let(bounds::add)
    return Rectangle2D.Double(bounds.x - 2.5, bounds.y - 2.5, bounds.width + 5, bounds.height + 5)
}
