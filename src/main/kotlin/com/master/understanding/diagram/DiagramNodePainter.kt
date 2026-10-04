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
    private val nodeBorder = JBColor(Color(0x64748B), Color(0x9AA4B2))
    private val focusBorder = JBColor(Color(0x2563EB), Color(0x72B7FF))
    private val focusHalo = JBColor(Color(37, 99, 235, 22), Color(114, 183, 255, 28))
    private val textColor = JBColor(Color(0x111827), Color(0xF2F4F7))
    private val actionFill = JBColor(Color(0xEEF4FF), Color(0x254663))
    private val actionBorder = JBColor(Color(0x2563EB), Color(0x72B7FF))
    private val startFill = JBColor(Color(0xDCEBFF), Color(0x25476C))

    fun copyBounds(node: Rectangle2D.Double): Rectangle2D.Double =
        Rectangle2D.Double(node.x + 8, node.maxY + 8, 28.0, 28.0)

    fun questionBounds(node: Rectangle2D.Double): Rectangle2D.Double =
        Rectangle2D.Double(node.x + 40, node.maxY + 8, 28.0, 28.0)

    fun noteBounds(node: Rectangle2D.Double): Rectangle2D.Double =
        Rectangle2D.Double(node.maxX - 20, node.y - 14, 28.0, 28.0)

    fun paint(
        g: Graphics2D,
        layout: UnderstandingGraphLayout,
        focused: String?,
        hovered: String?,
        questionCounts: Map<String, Int>,
        pendingNoteCounts: Map<String, Int>,
        startIds: Set<String>,
        endIds: Set<String>,
        choiceTargets: Set<String> = emptySet(),
        choicePhase: Float = 0f,
        copiedNodeId: String? = null,
        visible: Rectangle2D? = null,
        branchIds: Set<String> = emptySet(),
        draftNoteNodes: Set<String> = emptySet(),
        focusedChoice: String? = null,
    ) {
        g.font = UIManager.getFont("Label.font") ?: g.font
        layout.nodes.values.forEach { placed ->
            val bounds = placed.bounds
            val id = placed.node.id
            if (visible != null && !visible.intersects(bounds.x - 24, bounds.y - 24,
                    bounds.width + 48, bounds.height + 68)) return@forEach
            val current = id == focused
            if (current || id == hovered || id == focusedChoice) {
                g.color = focusHalo
                g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawRoundRect(bounds.x.toInt() - 2, bounds.y.toInt() - 2,
                    bounds.width.toInt() + 4, bounds.height.toInt() + 4, 10, 10)
            }
            if (id in choiceTargets) {
                val alpha = (90 + 120 * ((sin(choicePhase.toDouble()) + 1) / 2)).toInt()
                g.color = Color(255, 193, 77, alpha)
                g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawRoundRect(bounds.x.toInt() - 3, bounds.y.toInt() - 3,
                    bounds.width.toInt() + 6, bounds.height.toInt() + 6, 18, 18)
            }
            g.color = when (id) { in startIds -> startFill
                in endIds -> DiagramNodeSemantics.endFill(placed.node.endOutcome); else -> nodeFill }
            g.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 10, 10)
            g.color = when { id in endIds -> DiagramNodeSemantics.endBorder(placed.node.endOutcome); id in startIds -> focusBorder
                current || id == hovered || id in choiceTargets -> focusBorder; else -> nodeBorder }
            g.stroke = BasicStroke(if (id == focusedChoice) 3f else if (current) 2f else 1f)
            g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 10, 10)
            g.color = textColor
            paintLabel(g, placed, id in branchIds)
            if (id == hovered || id == copiedNodeId) {
                paintAction(g, copyBounds(bounds), if (id == copiedNodeId) "✓" else "⧉")
                if (id == hovered && (questionCounts[id] ?: 0) > 0) paintAction(g, questionBounds(bounds), "?")
            }
            val hasNote = (pendingNoteCounts[id] ?: 0) > 0 || id in draftNoteNodes
            if (hasNote || id == hovered) {
                paintAction(g, noteBounds(bounds), if (hasNote) "note" else "+")
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
        if (label == "note") {
            val x = bounds.x.toInt() + 7
            val y = bounds.y.toInt() + 7
            g.drawRoundRect(x, y, 14, 10, 4, 4)
            g.drawLine(x + 4, y + 10, x + 4, y + 14)
            g.drawLine(x + 4, y + 14, x + 8, y + 10)
            return
        }
        val metrics = g.fontMetrics
        g.drawString(label, (bounds.centerX - metrics.stringWidth(label) / 2).toFloat(),
            (bounds.centerY + metrics.ascent / 2.0 - 2).toFloat())
    }

    private fun paintLabel(g: Graphics2D, placed: PlacedNode, branch: Boolean) {
        val metrics = g.fontMetrics
        val inset = DiagramNodeSemantics.textInset(g.font, branch)
        val maxWidth = placed.bounds.width.toInt() - 24 - inset
        val lines = DiagramTextMetrics.lines(placed.node.label, maxWidth, g.font)
        val visible = lines.take(3).toMutableList()
        if (lines.size > 3 && visible.isNotEmpty()) {
            var last = visible.last()
            while (last.isNotEmpty() && metrics.stringWidth("$last…") > maxWidth) last = last.dropLast(1)
            visible[visible.lastIndex] = "$last…"
        }
        val startY = placed.bounds.y + 14 + metrics.ascent
        if (branch) DiagramNodeSemantics.paintBranch(g,
            DiagramNodeSemantics.iconBounds(placed.bounds, g.font, metrics.ascent, metrics.descent))
        visible.forEachIndexed { index, text ->
            g.drawString(text, (placed.bounds.x + 12 + inset).toFloat(), (startY + index * metrics.height).toFloat())
        }
    }
}
