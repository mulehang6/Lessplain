package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.UIManager

internal enum class DiagramNodeAction { COPY, QUESTIONS, NOTE }

internal data class DiagramPaintState(
    val layout: UnderstandingGraphLayout,
    val examplePanels: List<DiagramExamplePanel>,
    val width: Int,
    val height: Int,
    val zoom: Double,
    val centerX: Double,
    val centerY: Double,
    val currentNodeId: String?,
    val hoveredNodeId: String?,
    val hoveredAction: DiagramNodeAction?,
    val questionCounts: Map<String, Int>,
    val pendingNoteCounts: Map<String, Int>,
    val startIds: Set<String>,
    val endIds: Set<String>,
    val routeEdgeIds: Set<String>,
    val choiceTargets: Set<String>,
    val choicePhase: Float,
    val copiedNodeId: String?,
    val animateFlow: Boolean,
    val flowPhase: Float,
    val controlHeight: Int,
    val titleState: DiagramTitleState = DiagramTitleState(),
    val branchIds: Set<String> = emptySet(),
    val draftNoteNodes: Set<String> = emptySet(),
    val upstreamEdgeIds: Set<String> = emptySet(),
    val previewEdgeIds: Set<String>? = null,
)

/** Paints the canvas from a snapshot; input and navigation remain in the diagram view. */
internal object DiagramCanvasPainter {
    private val background = JBColor(Color(0xFAFBFC), Color(0x1E1F22))
    private val clusterFill = JBColor(Color(0xF5F8FC), Color(0x24272D))
    private val clusterBorder = JBColor(Color(0x9EACC0), Color(0x788494))
    private val labelColor = JBColor(Color(0x374151), Color(0xC4CBD5))

    fun paint(graphics: Graphics, state: DiagramPaintState) {
        val g = graphics.create() as Graphics2D
        try {
            g.clipRect(0, 0, state.width, state.height)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = background
            g.fillRect(0, 0, state.width, state.height)
            g.translate(state.width / 2.0, state.height / 2.0)
            g.scale(state.zoom, state.zoom)
            g.translate(-state.centerX, -state.centerY)
            val visible = g.clipBounds
            state.layout.clusters.forEach { cluster ->
                val b = cluster.bounds
                if (visible != null && !visible.intersects(b)) return@forEach
                g.color = clusterFill
                g.fillRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 18, 18)
                g.color = clusterBorder
                g.stroke = BasicStroke(2f)
                g.drawRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 18, 18)
                DiagramClusterText.paint(g, cluster, state.titleState)
                val panel = state.examplePanels.firstOrNull { it.clusterId == cluster.id }
                if (panel != null && cluster.exampleBounds != null) {
                    DiagramExamplePainter.paint(g, cluster.exampleBounds, panel, state.currentNodeId)
                }
            }
            DiagramEdgePainter.paint(g, state.layout.edges, state.routeEdgeIds, state.animateFlow,
                state.flowPhase, visible, state.upstreamEdgeIds, state.previewEdgeIds,
                state.layout.crossings, state.layout.clusters)
            DiagramNodePainter.paint(g, state.layout, state.currentNodeId, state.hoveredNodeId,
                state.questionCounts, state.pendingNoteCounts, state.startIds, state.endIds, state.choiceTargets,
                state.choicePhase, state.copiedNodeId, visible, state.branchIds, state.draftNoteNodes)
            if (state.choiceTargets.isNotEmpty()) {
                val overlay = graphics.create() as Graphics2D
                try {
                    overlay.color = labelColor
                    overlay.drawString("请选择闪烁的下一步节点", 16, state.controlHeight + 34)
                } finally { overlay.dispose() }
            }
            paintActionTooltip(graphics, state)
        } finally {
            g.dispose()
        }
    }

    private fun paintActionTooltip(graphics: Graphics, state: DiagramPaintState) {
        val id = state.hoveredNodeId ?: return
        val action = state.hoveredAction ?: return
        if (action == DiagramNodeAction.NOTE && (state.pendingNoteCounts[id] ?: 0) > 0) return
        val bounds = state.layout.nodes[id]?.bounds ?: return
        val actionBounds = when (action) {
            DiagramNodeAction.COPY -> DiagramNodePainter.copyBounds(bounds)
            DiagramNodeAction.QUESTIONS -> DiagramNodePainter.questionBounds(bounds)
            DiagramNodeAction.NOTE -> DiagramNodePainter.noteBounds(bounds)
        }
        val label = when (action) {
            DiagramNodeAction.COPY -> "复制"
            DiagramNodeAction.QUESTIONS -> "查看 ${state.questionCounts[id] ?: 0} 个追问"
            DiagramNodeAction.NOTE -> when {
                (state.pendingNoteCounts[id] ?: 0) > 0 -> "查看待答注释"
                id in state.draftNoteNodes -> "继续编辑注释"
                else -> "添加注释"
            }
        }
        val g = graphics.create() as Graphics2D
        try {
            g.font = UIManager.getFont("ToolTip.font") ?: g.font
            val metrics = g.fontMetrics
            val w = metrics.stringWidth(label) + 16
            val h = metrics.height + 8
            val x = (state.width / 2.0 + (actionBounds.centerX - state.centerX) * state.zoom - w / 2).toInt()
                .coerceIn(0, (state.width - w).coerceAtLeast(0))
            val y = (state.height / 2.0 + (actionBounds.y - state.centerY) * state.zoom - h - 5).toInt()
                .coerceIn(0, (state.height - h).coerceAtLeast(0))
            g.color = UIManager.getColor("ToolTip.background") ?: JBColor(Color(0xF1F1F1), Color(0x33363D))
            g.fillRoundRect(x, y, w, h, 8, 8)
            g.color = UIManager.getColor("ToolTip.foreground") ?: JBColor(Color.BLACK, Color.WHITE)
            g.drawString(label, x + 8, y + 4 + metrics.ascent)
        } finally { g.dispose() }
    }
}
