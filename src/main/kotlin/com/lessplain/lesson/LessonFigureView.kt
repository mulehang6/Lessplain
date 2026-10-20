package com.lessplain.lesson

import com.intellij.util.ui.UIUtil
import com.lessplain.diagram.DiagramCluster
import com.lessplain.diagram.UnderstandingGraphLayout
import com.lessplain.diagram.layoutUnderstandingClusters
import com.lessplain.diagram.layoutUnderstandingGraph
import com.lessplain.model.UnderstandingGraph
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.LineBreakMeasurer
import java.awt.geom.Rectangle2D
import java.text.AttributedString
import java.awt.font.TextAttribute
import javax.swing.JPanel

/** 复用 ELK 的布局结果，只提供对象批注；不启动动画，也不截取教材的滚轮来拖动图。 */
internal class LessonFigureView(
    service: LessonService,
    private val graph: UnderstandingGraph,
    private val reflow: (() -> Unit) -> Unit,
    private val selected: (String, String, MouseEvent) -> Unit,
) : JPanel(), AutoCloseable {
    private var placed: UnderstandingGraphLayout? = null
    private var failure: String? = null
    private var closed = false
    private var activeNode: String? = null
    init {
        isOpaque = false
        preferredSize = Dimension(600, 150)
        service.background({
            if (graph.clusters.isEmpty()) layoutUnderstandingGraph(graph)
            else layoutUnderstandingClusters(graph, graph.clusters.map { DiagramCluster(it.id, it.title, it.nodeIds.toSet()) })
        }, failed = { failure = "图示布局失败，请重新打开教材"; repaint() }) { result ->
            if (!closed) {
                reflow {
                    placed = result
                    preferredSize = Dimension(result.bounds.width.toInt() + 24, result.bounds.height.toInt() + 24)
                    revalidate(); repaint()
                }
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val layout = placed ?: return
                val x = event.x - 12 + layout.bounds.x
                val y = event.y - 12 + layout.bounds.y
                layout.nodes.values.firstOrNull { it.bounds.contains(x, y) }?.let {
                    activeNode = it.node.id
                    repaint()
                    selected(it.node.id, it.node.label, event)
                }
            }
        })
        getAccessibleContext().accessibleName = "教材结构图，选择节点可提问或写笔记"
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val layout = placed
        if (layout == null) {
            graphics.color = UIUtil.getLabelForeground()
            graphics.drawString(failure ?: "正在布局图示…", 12, 30)
            return
        }
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.translate(12 - layout.bounds.x, 12 - layout.bounds.y)
            g.font = UIUtil.getLabelFont()
            layout.clusters.forEach {
                g.color = UIUtil.getPanelBackground()
                g.fill(it.bounds)
                g.color = UIUtil.getLabelDisabledForeground()
                g.stroke = BasicStroke(1f)
                g.draw(it.bounds)
                g.drawString(it.title, (it.bounds.x + 12).toFloat(), (it.bounds.y + 22).toFloat())
            }
            layout.edges.forEach {
                g.color = UIUtil.getLabelDisabledForeground()
                g.stroke = BasicStroke(1.3f)
                g.draw(it.path)
                g.fill(it.arrowPath)
                val label = it.edge.condition ?: it.edge.summary
                if (!label.isNullOrBlank()) {
                    val fm = g.fontMetrics
                    g.drawString(label, (it.labelX - fm.stringWidth(label) / 2).toFloat(), it.labelY.toFloat())
                }
            }
            layout.nodes.values.forEach {
                g.color = UIUtil.getTextFieldBackground()
                g.fillRoundRect(it.bounds.x.toInt(), it.bounds.y.toInt(), it.bounds.width.toInt(), it.bounds.height.toInt(), 10, 10)
                g.color = if (it.node.id == activeNode) UIUtil.getListSelectionBackground(true) else UIUtil.getLabelDisabledForeground()
                g.stroke = BasicStroke(if (it.node.id == activeNode) 2f else 1f)
                g.drawRoundRect(it.bounds.x.toInt(), it.bounds.y.toInt(), it.bounds.width.toInt(), it.bounds.height.toInt(), 10, 10)
                g.color = UIUtil.getLabelForeground()
                g.font = it.font ?: UIUtil.getLabelFont()
                drawLessonLabel(g, it.node.label, it.bounds)
            }
        } finally { g.dispose() }
    }

    override fun close() { closed = true }
}

/** 中文和长标识符都按实际字体换行，避免缩小整张图导致文字难读。 */
internal fun drawLessonLabel(g: Graphics2D, text: String, bounds: Rectangle2D) {
    var y = bounds.y + 10
    text.lines().forEach { line ->
        if (line.isEmpty()) { y += g.fontMetrics.height; return@forEach }
        val attributed = AttributedString(line).apply { addAttribute(TextAttribute.FONT, g.font) }
        val breaker = LineBreakMeasurer(attributed.iterator, g.fontRenderContext)
        while (breaker.position < line.length) {
            val run = breaker.nextLayout((bounds.width - 24).coerceAtLeast(20.0).toFloat())
            y += run.ascent
            run.draw(g, (bounds.centerX - run.advance / 2).toFloat(), y.toFloat())
            y += run.descent + run.leading
        }
    }
}
