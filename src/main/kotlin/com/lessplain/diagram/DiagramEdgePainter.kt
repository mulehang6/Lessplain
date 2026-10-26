package com.lessplain.diagram

import com.lessplain.ui.UnderstandingUiStyle
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.geom.Path2D
import kotlin.math.abs

/** 绘制有向边，并为所有高亮阅读路径提供一致的流动动画。 */
internal object DiagramEdgePainter {
    private val background get() = UnderstandingUiStyle.canvas
    private val edgeColor get() = UnderstandingUiStyle.edge
    private val routeColor get() = UnderstandingUiStyle.accent
    private val flowColor get() = UnderstandingUiStyle.flow
    private val labelColor get() = UnderstandingUiStyle.muted
    private val choiceColor get() = UnderstandingUiStyle.warning
    private val dashPattern = floatArrayOf(10f, 10f)

    fun paint(g: Graphics2D, edges: List<PlacedEdge>, routeIds: Set<String>, animate: Boolean,
        phase: Float, visible: Rectangle2D? = null, crossings: List<DiagramCrossing> = emptyList(),
        clusters: List<PlacedCluster> = emptyList(), choiceIds: Set<String> = emptySet(),
        focusedChoice: String? = null) {
        g.font = DiagramTextMetrics.font()
        val pixelMargin = 1.0 / kotlin.math.hypot(g.transform.scaleX, g.transform.shearY).coerceAtLeast(0.01)
        val clipping = visible?.let {
            Rectangle2D.Double(it.x - pixelMargin, it.y - pixelMargin,
                it.width + pixelMargin * 2, it.height + pixelMargin * 2)
        }
        edges.sortedBy { priority(it.edge.id, routeIds, choiceIds, focusedChoice) }.forEach { placed ->
            if (clipping != null && !clipping.intersects(placed.paintBounds)) return@forEach
            val candidate = placed.edge.id in choiceIds
            val focused = placed.edge.id == focusedChoice
            val active = placed.edge.id in routeIds && !candidate
            g.color = when { focused -> routeColor; candidate -> choiceColor; active -> routeColor; else -> edgeColor }
            g.stroke = edgeStroke(placed.edge.inferred == true,
                when { focused -> 4f; candidate -> 2.8f; active -> 2.4f; else -> 1.2f })
            g.draw(placed.path)
            g.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(placed.arrowPath)
            if (active && animate) {
                g.color = flowColor
                g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dashPattern, phase)
                g.draw(placed.path)
            }
        }
        paintBridges(g, edges, crossings, routeIds, clusters, clipping, choiceIds, focusedChoice)
        edges.forEach { placed ->
            if (clipping == null || clipping.intersects(placed.paintBounds)) {
                placed.edge.condition?.takeIf(String::isNotBlank)?.let {
                    paintEdgeLabel(g, it, placed, placed.edge.id in routeIds,
                        placed.edge.id in choiceIds, placed.edge.id == focusedChoice)
                }
            }
        }
    }

    /** 用小圆弧区分几何交叉和图中的连接点。 */
    private fun paintBridges(g: Graphics2D, edges: List<PlacedEdge>, crossings: List<DiagramCrossing>,
        highlighted: Set<String>, clusters: List<PlacedCluster>, visible: Rectangle2D?,
        choiceIds: Set<String>, focusedChoice: String?) {
        if (crossings.isEmpty()) return
        val byId = edges.associateBy { it.edge.id }
        for (crossing in crossings) {
            val point = crossing.point
            if (visible != null && !visible.intersects(point.x - 8, point.y - 8, 16.0, 16.0)) continue
            val top = if (priority(crossing.firstEdgeId, highlighted, choiceIds, focusedChoice) >
                priority(crossing.secondEdgeId, highlighted, choiceIds, focusedChoice))
                byId.getValue(crossing.firstEdgeId) else byId.getValue(crossing.secondEdgeId)
            val horizontal = top.segments.any { abs(it.y1 - it.y2) < 0.01 && abs(it.y1 - point.y) < 0.01 &&
                point.x > minOf(it.x1, it.x2) && point.x < maxOf(it.x1, it.x2) }
            val bridge = Path2D.Double().apply {
                if (horizontal) { moveTo(point.x - 5, point.y); quadTo(point.x, point.y - 7, point.x + 5, point.y) }
                else { moveTo(point.x, point.y - 5); quadTo(point.x + 7, point.y, point.x, point.y + 5) }
            }
            g.color = if (clusters.any { it.bounds.contains(point) })
                UnderstandingUiStyle.group else background
            g.stroke = BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (horizontal) g.drawLine((point.x - 5).toInt(), point.y.toInt(), (point.x + 5).toInt(), point.y.toInt())
            else g.drawLine(point.x.toInt(), (point.y - 5).toInt(), point.x.toInt(), (point.y + 5).toInt())
            g.draw(bridge)
            g.color = when (top.edge.id) { focusedChoice -> routeColor; in choiceIds -> choiceColor
                in highlighted -> routeColor; else -> edgeColor }
            g.stroke = edgeStroke(top.edge.inferred == true, when (top.edge.id) { focusedChoice -> 4f; in choiceIds -> 2.8f
                in highlighted -> 2.4f; else -> 1.2f })
            g.draw(bridge)
        }
    }

    private fun priority(id: String, route: Set<String>, choices: Set<String>, focused: String?): Int =
        when (id) { focused -> 3; in choices -> 2; in route -> 1; else -> 0 }

    /** 推断样式独立于关系类型和活动路径，动画仍使用原有叠加层。 */
    internal fun edgeStroke(inferred: Boolean, width: Float): BasicStroke =
        if (inferred) BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            10f, floatArrayOf(5f, 4f), 0f)
        else BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    private fun paintEdgeLabel(g: Graphics2D, label: String, placed: PlacedEdge, active: Boolean,
        candidate: Boolean, focused: Boolean) {
        val text = label.take(32)
        val metrics = g.fontMetrics
        val bounds = requireNotNull(placed.resolvedLabelBounds)
        val w = bounds.width.toInt()
        val h = bounds.height.toInt()
        val x = bounds.x
        val y = bounds.y
        g.color = background
        g.fillRoundRect(x.toInt(), y.toInt(), w, h, 8, 8)
        g.color = when { focused -> routeColor; candidate -> choiceColor; active -> routeColor; else -> labelColor }
        if (candidate) {
            g.stroke = BasicStroke(if (focused) 2f else 1f)
            g.drawRoundRect(x.toInt(), y.toInt(), w, h, 8, 8)
        }
        g.drawString(text, x.toFloat() + 6, y.toFloat() + metrics.ascent + 2)
    }
}
