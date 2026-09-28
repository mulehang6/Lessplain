package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Path2D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Directed edges and animated flow for the current reading route. */
internal object DiagramEdgePainter {
    private val background = JBColor(Color(0xFAFBFC), Color(0x1E1F22))
    private val edgeColor = JBColor(Color(0x596579), Color(0xB1BAC7))
    private val routeColor = JBColor(Color(0x2563EB), Color(0x72B7FF))
    private val flowColor = JBColor(Color(0xDCEBFF), Color(0xE2F3FF))
    private val labelColor = JBColor(Color(0x374151), Color(0xC4CBD5))
    private val dashPattern = floatArrayOf(10f, 10f)

    fun paint(g: Graphics2D, edges: List<PlacedEdge>, routeIds: Set<String>, animate: Boolean, phase: Float) {
        edges.forEach { placed ->
            val active = placed.edge.id in routeIds
            g.color = if (active) routeColor else edgeColor
            g.stroke = BasicStroke(if (active) 3f else 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(placed.path)
            paintArrow(g, placed)
            if (active && animate) {
                g.color = flowColor
                g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dashPattern, phase)
                g.draw(placed.path)
            }
            placed.edge.condition?.takeIf(String::isNotBlank)?.let { paintEdgeLabel(g, it, placed, active) }
        }
    }

    private fun paintArrow(g: Graphics2D, placed: PlacedEdge) {
        val angle = atan2(placed.arrowDy, placed.arrowDx)
        val x = placed.arrowX
        val y = placed.arrowY
        val arrow = Path2D.Double().apply {
            moveTo(x, y)
            lineTo(x - 11 * cos(angle - 0.45), y - 11 * sin(angle - 0.45))
            moveTo(x, y)
            lineTo(x - 11 * cos(angle + 0.45), y - 11 * sin(angle + 0.45))
        }
        g.draw(arrow)
    }

    private fun paintEdgeLabel(g: Graphics2D, label: String, placed: PlacedEdge, active: Boolean) {
        val text = label.take(32)
        val metrics = g.fontMetrics
        val w = metrics.stringWidth(text) + 12
        val h = metrics.height + 4
        val x = placed.labelX - w / 2
        val y = placed.labelY - h / 2
        g.color = background
        g.fillRoundRect(x.toInt(), y.toInt(), w, h, 8, 8)
        g.color = if (active) routeColor else labelColor
        g.drawString(text, x.toFloat() + 6, y.toFloat() + metrics.ascent + 2)
    }
}
