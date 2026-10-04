package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.geom.Path2D
import kotlin.math.abs

/** Directed edges and uniform animated flow for every highlighted reading path. */
internal object DiagramEdgePainter {
    private val background = JBColor(Color(0xFAFBFC), Color(0x1E1F22))
    private val edgeColor = JBColor(Color(0x596579), Color(0xB1BAC7))
    private val routeColor = JBColor(Color(0x2563EB), Color(0x72B7FF))
    private val flowColor = JBColor(Color(0xDCEBFF), Color(0xE2F3FF))
    private val labelColor = JBColor(Color(0x374151), Color(0xC4CBD5))
    private val choiceColor = JBColor(Color(0xB66A00), Color(0xFFC14D))
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
        edges.sortedBy { when (it.edge.id) { focusedChoice -> 3; in choiceIds -> 2; in routeIds -> 1; else -> 0 } }.forEach { placed ->
            if (clipping != null && !clipping.intersects(placed.paintBounds)) return@forEach
            val candidate = placed.edge.id in choiceIds
            val focused = placed.edge.id == focusedChoice
            val active = placed.edge.id in routeIds && !candidate
            g.color = when { focused -> routeColor; candidate -> choiceColor; active -> routeColor; else -> edgeColor }
            g.stroke = BasicStroke(when { focused -> 4f; candidate -> 2.8f; active -> 2.4f; else -> 1.2f },
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(placed.path)
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

    /** A small arc makes geometric crossings visibly distinct from graph junctions. */
    private fun paintBridges(g: Graphics2D, edges: List<PlacedEdge>, crossings: List<DiagramCrossing>,
        highlighted: Set<String>, clusters: List<PlacedCluster>, visible: Rectangle2D?,
        choiceIds: Set<String>, focusedChoice: String?) {
        if (crossings.isEmpty()) return
        val byId = edges.associateBy { it.edge.id }
        for (crossing in crossings) {
            val point = crossing.point
            if (visible != null && !visible.intersects(point.x - 8, point.y - 8, 16.0, 16.0)) continue
            val top = if (crossing.firstEdgeId in highlighted && crossing.secondEdgeId !in highlighted)
                byId.getValue(crossing.firstEdgeId) else byId.getValue(crossing.secondEdgeId)
            val horizontal = top.segments.any { abs(it.y1 - it.y2) < 0.01 && abs(it.y1 - point.y) < 0.01 &&
                point.x > minOf(it.x1, it.x2) && point.x < maxOf(it.x1, it.x2) }
            val bridge = Path2D.Double().apply {
                if (horizontal) { moveTo(point.x - 5, point.y); quadTo(point.x, point.y - 7, point.x + 5, point.y) }
                else { moveTo(point.x, point.y - 5); quadTo(point.x + 7, point.y, point.x, point.y + 5) }
            }
            g.color = if (clusters.any { it.bounds.contains(point) })
                JBColor(Color(0xF5F8FC), Color(0x24272D)) else background
            g.stroke = BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (horizontal) g.drawLine((point.x - 5).toInt(), point.y.toInt(), (point.x + 5).toInt(), point.y.toInt())
            else g.drawLine(point.x.toInt(), (point.y - 5).toInt(), point.x.toInt(), (point.y + 5).toInt())
            g.draw(bridge)
            g.color = when (top.edge.id) { focusedChoice -> routeColor; in choiceIds -> choiceColor
                in highlighted -> routeColor; else -> edgeColor }
            g.stroke = BasicStroke(when (top.edge.id) { focusedChoice -> 4f; in choiceIds -> 2.8f
                in highlighted -> 2.4f; else -> 1.2f }, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(bridge)
        }
    }

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
