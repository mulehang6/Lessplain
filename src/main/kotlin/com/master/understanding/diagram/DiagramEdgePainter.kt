package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/** Directed edges and animated flow for the current reading route. */
internal object DiagramEdgePainter {
    private val background = JBColor(Color(0xFAFBFC), Color(0x1E1F22))
    private val edgeColor = JBColor(Color(0x596579), Color(0xB1BAC7))
    private val routeColor = JBColor(Color(0x2563EB), Color(0x72B7FF))
    private val flowColor = JBColor(Color(0xDCEBFF), Color(0xE2F3FF))
    private val labelColor = JBColor(Color(0x374151), Color(0xC4CBD5))
    private val dashPattern = floatArrayOf(10f, 10f)

    fun paint(g: Graphics2D, edges: List<PlacedEdge>, routeIds: Set<String>, animate: Boolean,
        phase: Float, visible: Rectangle2D? = null) {
        g.font = DiagramTextMetrics.font()
        val pixelMargin = 1.0 / kotlin.math.hypot(g.transform.scaleX, g.transform.shearY).coerceAtLeast(0.01)
        val clipping = visible?.let {
            Rectangle2D.Double(it.x - pixelMargin, it.y - pixelMargin,
                it.width + pixelMargin * 2, it.height + pixelMargin * 2)
        }
        edges.forEach { placed ->
            if (clipping != null && !clipping.intersects(placed.paintBounds)) return@forEach
            val active = placed.edge.id in routeIds
            g.color = if (active) routeColor else edgeColor
            g.stroke = BasicStroke(if (active) 2.4f else 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(placed.path)
            g.draw(placed.arrowPath)
            if (active && animate) {
                g.color = flowColor
                g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dashPattern, phase)
                g.draw(placed.path)
            }
            placed.edge.condition?.takeIf(String::isNotBlank)?.let { paintEdgeLabel(g, it, placed, active) }
        }
    }

    private fun paintEdgeLabel(g: Graphics2D, label: String, placed: PlacedEdge, active: Boolean) {
        val text = label.take(32)
        val metrics = g.fontMetrics
        val bounds = requireNotNull(placed.resolvedLabelBounds)
        val w = bounds.width.toInt()
        val h = bounds.height.toInt()
        val x = bounds.x
        val y = bounds.y
        g.color = background
        g.fillRoundRect(x.toInt(), y.toInt(), w, h, 8, 8)
        g.color = if (active) routeColor else labelColor
        g.drawString(text, x.toFloat() + 6, y.toFloat() + metrics.ascent + 2)
    }
}
