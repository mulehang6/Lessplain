package com.lessplain.diagram

import com.lessplain.ui.UnderstandingUiStyle
import com.lessplain.ui.UnderstandingUiMetrics
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** 在解释流程旁展示一个假设的请求对象。 */
internal data class DiagramExamplePanel(
    val clusterId: String,
    val parameterName: String,
    val sampleValue: String,
    val missingNodeIds: Set<String>,
    val focusByNode: Map<String, String> = emptyMap(),
)

internal object DiagramExamplePainter {
    private const val TITLE = "HttpServletRequest（示例）"
    private val padding get() = JBUI.scale(UnderstandingUiMetrics.DETAIL_PADDING)
    private val gap get() = JBUI.scale(UnderstandingUiMetrics.SECTION_GAP)
    private val rowPadding get() = JBUI.scale(UnderstandingUiMetrics.GAP)
    val PANEL_WIDTH: Int get() = measure { maxOf(JBUI.scale(260), it.stringWidth(TITLE) + padding * 2) }
    val PANEL_HEIGHT: Int get() = measure {
        padding * 2 + it.height * 2 + gap * 3 + (it.height + rowPadding * 2) * 5
    }
    private val background get() = UnderstandingUiStyle.surface
    private val border get() = UnderstandingUiStyle.border
    private val highlight get() = UnderstandingUiStyle.focusSurface
    private val text get() = UnderstandingUiStyle.foreground
    private val muted get() = UnderstandingUiStyle.muted

    fun paint(g: Graphics2D, bounds: Rectangle2D.Double, panel: DiagramExamplePanel, focusedNodeId: String?) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.clip(bounds)
            paintContent(graphics, bounds, panel, focusedNodeId)
        } finally { graphics.dispose() }
    }

    /** 布局和绘制共用随字体变化的行高，IDE 字体增大时也保持一致。 */
    private fun paintContent(g: Graphics2D, bounds: Rectangle2D.Double, panel: DiagramExamplePanel, focusedNodeId: String?) {
        val x = bounds.x.toInt()
        val y = bounds.y.toInt()
        val w = bounds.width.toInt()
        val h = bounds.height.toInt()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.font = DiagramTextMetrics.font()
        g.color = background
        g.fillRoundRect(x, y, w, h, UnderstandingUiStyle.GROUP_ARC, UnderstandingUiStyle.GROUP_ARC)
        g.color = border
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(x, y, w, h, UnderstandingUiStyle.GROUP_ARC, UnderstandingUiStyle.GROUP_ARC)
        val metrics = g.fontMetrics
        val rowHeight = metrics.height + rowPadding * 2
        g.color = text
        g.drawString(TITLE, x + padding, y + padding + metrics.ascent)
        var top = y + padding + metrics.height + gap
        field(g, x, top, w, "method", "POST", false)
        top += rowHeight
        field(g, x, top, w, "path", "/login", false)
        top += rowHeight
        val value = if (focusedNodeId in panel.missingNodeIds) "（缺失）" else panel.sampleValue
        val focus = panel.focusByNode[focusedNodeId] ?: "parameter"
        field(g, x, top, w, "parameters.${panel.parameterName}", value, focus == "parameter")
        top += rowHeight
        g.color = border
        g.drawLine(x + padding, top + gap / 2, x + w - padding, top + gap / 2)
        top += gap
        field(g, x, top, w, "返回值", if (focusedNodeId in panel.missingNodeIds) "null" else "\"${panel.sampleValue}\"",
            focus == "result")
        top += rowHeight
        field(g, x, top, w, "局部变量", if (focus == "variable") panel.sampleValue else "—",
            focus == "variable")
        g.color = muted
        g.drawString("示意数据，不是真实请求", x + padding, y + h - padding - metrics.descent)
    }

    private fun field(g: Graphics2D, x: Int, y: Int, width: Int, name: String, value: String, focused: Boolean) {
        if (focused) {
            g.color = highlight
            g.fillRoundRect(x + padding, y, width - padding * 2, g.fontMetrics.height + rowPadding * 2,
                UnderstandingUiStyle.NODE_ARC, UnderstandingUiStyle.NODE_ARC)
        }
        val baseline = y + rowPadding + g.fontMetrics.ascent
        val maxValueWidth = (width - padding * 2 - rowPadding * 2) / 3
        val maxNameWidth = width - padding * 2 - rowPadding * 2 - maxValueWidth - gap
        g.color = muted
        g.drawString(elide(g, name, maxNameWidth), x + padding + rowPadding, baseline)
        g.color = text
        val shown = elide(g, value, maxValueWidth)
        g.drawString(shown, x + width - padding - rowPadding - g.fontMetrics.stringWidth(shown), baseline)
    }

    private fun elide(g: Graphics2D, text: String, available: Int): String {
        if (g.fontMetrics.stringWidth(text) <= available) return text
        var shown = text
        while (shown.isNotEmpty() && g.fontMetrics.stringWidth("$shown…") > available) {
            shown = shown.substring(0, shown.offsetByCodePoints(shown.length, -1))
        }
        return if (g.fontMetrics.stringWidth("…") <= available) "$shown…" else ""
    }

    private fun measure(block: (java.awt.FontMetrics) -> Int): Int {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        return try {
            g.font = DiagramTextMetrics.font()
            block(g.fontMetrics)
        } finally { g.dispose() }
    }
}
