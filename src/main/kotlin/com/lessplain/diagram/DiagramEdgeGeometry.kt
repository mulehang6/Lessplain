package com.lessplain.diagram

import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 绘制、裁剪和局部布线预留共用箭头几何。 */
internal fun edgeArrowPath(edge: PlacedEdge): Path2D.Double {
    val angle = atan2(edge.arrowDy, edge.arrowDx)
    return Path2D.Double().apply {
        moveTo(edge.arrowX, edge.arrowY)
        lineTo(edge.arrowX - 11 * cos(angle - 0.45), edge.arrowY - 11 * sin(angle - 0.45))
        moveTo(edge.arrowX, edge.arrowY)
        lineTo(edge.arrowX - 11 * cos(angle + 0.45), edge.arrowY - 11 * sin(angle + 0.45))
    }
}

/** 没有 ELK 标签边界的旧路径使用与绘制器相同的字体测量。 */
internal fun edgeLabelBounds(edge: PlacedEdge): Rectangle2D.Double? {
    val label = edge.edge.condition?.takeIf(String::isNotBlank) ?: return null
    edge.labelBounds?.let { return it }
    val (width, height) = DiagramTextMetrics.edgeLabelSize(label)
    return Rectangle2D.Double(edge.labelX - width / 2, edge.labelY - height / 2, width, height)
}

/** 非退化边界包含最宽的动画描边及抗锯齿余量。 */
internal fun edgePaintBounds(edge: PlacedEdge): Rectangle2D.Double {
    val bounds = Rectangle2D.Double().apply { setRect(edge.path.bounds2D) }
    bounds.add(edge.arrowPath.bounds2D)
    edge.resolvedLabelBounds?.let(bounds::add)
    return Rectangle2D.Double(bounds.x - 2.5, bounds.y - 2.5, bounds.width + 5, bounds.height + 5)
}
