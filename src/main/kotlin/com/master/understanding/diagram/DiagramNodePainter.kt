package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import javax.swing.UIManager
import kotlin.math.sin

/** Shared node appearance and hover actions for root and follow-up canvases. */
internal object DiagramNodePainter {
    private val nodeFill = JBColor(Color.WHITE, Color(0x36383D))
    private val startFill = JBColor(Color(0xDDF7EE), Color(0x22513F))
    private val endFill = JBColor(Color(0xFDE8E8), Color(0x713039))
    private val nodeBorder = JBColor(Color(0x64748B), Color(0x9AA4B2))
    private val focusBorder = JBColor(Color(0xB45309), Color(0xFFCA70))
    private val focusHalo = JBColor(Color(217, 119, 6, 45), Color(255, 202, 112, 70))
    private val textColor = JBColor(Color(0x111827), Color(0xF2F4F7))
    private val actionFill = JBColor(Color(0xEEF4FF), Color(0x254663))
    private val actionBorder = JBColor(Color(0x2563EB), Color(0x72B7FF))

    fun copyBounds(node: Rectangle2D.Double): Rectangle2D.Double =
        Rectangle2D.Double(node.x + 8, node.maxY + 8, 28.0, 28.0)

    fun questionBounds(node: Rectangle2D.Double): Rectangle2D.Double =
        Rectangle2D.Double(node.x + 40, node.maxY + 8, 28.0, 28.0)

    fun paint(
        g: Graphics2D,
        layout: UnderstandingGraphLayout,
        focused: String?,
        hovered: String?,
        questionCounts: Map<String, Int>,
        startIds: Set<String>,
        endIds: Set<String>,
        choiceTargets: Set<String> = emptySet(),
        choicePhase: Float = 0f,
        copiedNodeId: String? = null,
    ) {
        g.font = UIManager.getFont("Label.font") ?: g.font
        layout.nodes.values.forEach { placed ->
            val bounds = placed.bounds
            val id = placed.node.id
            val current = id == focused
            if (current) {
                g.color = focusHalo
                g.stroke = BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawRoundRect(bounds.x.toInt() - 3, bounds.y.toInt() - 3,
                    bounds.width.toInt() + 6, bounds.height.toInt() + 6, 18, 18)
            }
            if (id in choiceTargets) {
                val alpha = (90 + 120 * ((sin(choicePhase.toDouble()) + 1) / 2)).toInt()
                g.color = Color(255, 193, 77, alpha)
                g.stroke = BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawRoundRect(bounds.x.toInt() - 3, bounds.y.toInt() - 3,
                    bounds.width.toInt() + 6, bounds.height.toInt() + 6, 18, 18)
            }
            g.color = when {
                placed.node.kind == "placeholder" -> nodeFill
                id in endIds -> endFill
                id in startIds -> startFill
                else -> nodeFill
            }
            g.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 14, 14)
            g.color = if (current) focusBorder else nodeBorder
            g.stroke = BasicStroke(if (current) 4f else 1.5f)
            g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 14, 14)
            g.color = textColor
            paintLabel(g, placed)
            if (id == hovered || id == copiedNodeId) {
                paintAction(g, copyBounds(bounds), if (id == copiedNodeId) "✓" else "⧉")
                if (id == hovered && (questionCounts[id] ?: 0) > 0) paintAction(g, questionBounds(bounds), "?")
            }
        }
    }

    private fun paintAction(g: Graphics2D, bounds: Rectangle2D.Double, label: String) {
        g.color = actionFill
        g.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 8, 8)
        g.color = actionBorder
        g.stroke = BasicStroke(1.5f)
        g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 8, 8)
        g.color = textColor
        val metrics = g.fontMetrics
        g.drawString(label, (bounds.centerX - metrics.stringWidth(label) / 2).toFloat(),
            (bounds.centerY + metrics.ascent / 2.0 - 2).toFloat())
    }

    private fun paintLabel(g: Graphics2D, placed: PlacedNode) {
        val metrics = g.fontMetrics
        val maxWidth = placed.bounds.width.toInt() - 24
        val lines = mutableListOf<String>()
        placed.node.label.split('\n').forEach { paragraph ->
            var line = ""
            paragraph.codePoints().forEach { codePoint ->
                val next = line + String(Character.toChars(codePoint))
                if (line.isNotEmpty() && metrics.stringWidth(next) > maxWidth) {
                    lines += line
                    line = String(Character.toChars(codePoint))
                } else line = next
            }
            lines += line
        }
        val visible = lines.take(3).toMutableList()
        if (lines.size > 3 && visible.isNotEmpty()) {
            var last = visible.last()
            while (last.isNotEmpty() && metrics.stringWidth("$last…") > maxWidth) last = last.dropLast(1)
            visible[visible.lastIndex] = "$last…"
        }
        val startY = placed.bounds.centerY - (visible.size - 1) * metrics.height / 2.0 + metrics.ascent / 2.0
        visible.forEachIndexed { index, text ->
            val x = placed.bounds.centerX - metrics.stringWidth(text) / 2.0
            g.drawString(text, x.toFloat(), (startY + index * metrics.height).toFloat())
        }
    }
}
