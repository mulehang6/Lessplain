package com.master.understanding.diagram

import com.master.understanding.ui.UnderstandingControlPainter
import com.master.understanding.ui.UnderstandingUiStyle
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import kotlin.math.sin

/** Shared node appearance and hover actions for root and follow-up canvases. */
internal object DiagramNodePainter {
    private val nodeFill get() = UnderstandingUiStyle.surface
    private val nodeBorder get() = UnderstandingUiStyle.border
    private val focusBorder get() = UnderstandingUiStyle.graphSelection
    private val focusHalo get() = UnderstandingUiStyle.graphSelectionHalo
    private val textColor get() = UnderstandingUiStyle.foreground
    private val startFill get() = UnderstandingUiStyle.focusSurface

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
        hoveredAction: DiagramNodeAction? = null,
    ) {
        g.font = DiagramTextMetrics.font()
        layout.nodes.values.forEach { placed ->
            val bounds = placed.bounds
            val id = placed.node.id
            if (visible != null && !visible.intersects(bounds.x - 24, bounds.y - 24,
                    bounds.width + 48, bounds.height + 68)) return@forEach
            val current = id == focused
            val preview = id == focusedChoice && !current
            if (current || (id == hovered && !preview)) {
                g.color = if (current) focusHalo else UnderstandingUiStyle.alpha(UnderstandingUiStyle.nodeHoverBorder, 36)
                g.stroke = BasicStroke(if (current) 4f else 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawRoundRect(bounds.x.toInt() - 2, bounds.y.toInt() - 2,
                    bounds.width.toInt() + 4, bounds.height.toInt() + 4,
                    UnderstandingUiStyle.NODE_ARC, UnderstandingUiStyle.NODE_ARC)
            }
            if (id in choiceTargets) {
                val alpha = (90 + 120 * ((sin(choicePhase.toDouble()) + 1) / 2)).toInt()
                g.color = UnderstandingUiStyle.alpha(UnderstandingUiStyle.warning, alpha)
                g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawRoundRect(bounds.x.toInt() - 3, bounds.y.toInt() - 3,
                    bounds.width.toInt() + 6, bounds.height.toInt() + 6, 18, 18)
            }
            g.color = when (id) { in startIds -> startFill
                in endIds -> DiagramNodeSemantics.endFill(placed.node.endOutcome)
                focused, focusedChoice -> UnderstandingUiStyle.graphSelectionSurface
                else -> nodeFill }
            g.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(),
                UnderstandingUiStyle.NODE_ARC, UnderstandingUiStyle.NODE_ARC)
            g.color = when {
                current || preview -> focusBorder
                id in endIds -> DiagramNodeSemantics.endBorder(placed.node.endOutcome)
                id in startIds -> UnderstandingUiStyle.accent
                id == hovered -> UnderstandingUiStyle.nodeHoverBorder
                else -> nodeBorder
            }
            g.stroke = if (preview) BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                10f, floatArrayOf(6f, 4f), 0f)
            else BasicStroke(if (current || id in startIds) 2f else 1f)
            g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(),
                UnderstandingUiStyle.NODE_ARC, UnderstandingUiStyle.NODE_ARC)
            g.color = textColor
            paintLabel(g, placed, id in branchIds)
            if (id == hovered || id == copiedNodeId) {
                paintAction(g, copyBounds(bounds),
                    if (id == copiedNodeId) GraphControlIconKind.COPIED else GraphControlIconKind.COPY,
                    id == hovered && hoveredAction == DiagramNodeAction.COPY)
                if (id == hovered && (questionCounts[id] ?: 0) > 0) {
                    paintAction(g, questionBounds(bounds), GraphControlIconKind.QUESTIONS,
                        hoveredAction == DiagramNodeAction.QUESTIONS)
                }
            }
            val hasNote = (pendingNoteCounts[id] ?: 0) > 0 || id in draftNoteNodes
            if (hasNote || id == hovered) {
                paintAction(g, noteBounds(bounds),
                    if (hasNote) GraphControlIconKind.NOTE else GraphControlIconKind.ADD,
                    id == hovered && hoveredAction == DiagramNodeAction.NOTE)
            }
        }
    }

    private fun paintAction(g: Graphics2D, bounds: Rectangle2D.Double, kind: GraphControlIconKind, hovered: Boolean) {
        UnderstandingControlPainter.paintBackground(g, bounds, hovered = hovered)
        val icon = GraphControlIcon(kind)
        icon.paintIcon(null, g, (bounds.centerX - icon.iconWidth / 2.0).toInt(),
            (bounds.centerY - icon.iconHeight / 2.0).toInt())
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
