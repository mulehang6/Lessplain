package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import javax.swing.UIManager

/** A hypothetical request object shown beside an explanation flow. */
internal data class DiagramExamplePanel(
    val clusterId: String,
    val parameterName: String,
    val sampleValue: String,
    val missingNodeIds: Set<String>,
    val focusByNode: Map<String, String> = emptyMap(),
)

internal object DiagramExamplePainter {
    const val PANEL_WIDTH = 260
    const val PANEL_HEIGHT = 222
    private val background = JBColor(Color(0xFFFFFF), Color(0x292B30))
    private val border = JBColor(Color(0xCBD5E1), Color(0x626B78))
    private val highlight = JBColor(Color(0xFFF3C4), Color(0x644B22))
    private val text = JBColor(Color(0x111827), Color(0xF2F4F7))
    private val muted = JBColor(Color(0x475569), Color(0xAFB8C5))

    fun paint(g: Graphics2D, bounds: Rectangle2D.Double, panel: DiagramExamplePanel, focusedNodeId: String?) {
        val x = bounds.x.toInt()
        val y = bounds.y.toInt()
        val w = bounds.width.toInt()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.font = UIManager.getFont("Label.font") ?: g.font
        g.color = background
        g.fillRoundRect(x, y, w, PANEL_HEIGHT, 12, 12)
        g.color = border
        g.stroke = BasicStroke(1.5f)
        g.drawRoundRect(x, y, w, PANEL_HEIGHT, 12, 12)
        g.color = text
        g.drawString("HttpServletRequest（示例）", x + 12, y + 24)
        field(g, x, y + 32, w, "method", "POST", false)
        field(g, x, y + 63, w, "path", "/login", false)
        val value = if (focusedNodeId in panel.missingNodeIds) "（缺失）" else panel.sampleValue
        val focus = panel.focusByNode[focusedNodeId] ?: "parameter"
        field(g, x, y + 94, w, "parameters.${panel.parameterName}", value, focus == "parameter")
        g.color = border
        g.drawLine(x + 12, y + 130, x + w - 12, y + 130)
        field(g, x, y + 136, w, "返回值", if (focusedNodeId in panel.missingNodeIds) "null" else "\"${panel.sampleValue}\"",
            focus == "result")
        field(g, x, y + 167, w, "局部变量", if (focus == "variable") panel.sampleValue else "—",
            focus == "variable")
        g.color = muted
        g.drawString("示意数据，不是真实请求", x + 12, y + PANEL_HEIGHT - 11)
    }

    private fun field(g: Graphics2D, x: Int, y: Int, width: Int, name: String, value: String, focused: Boolean) {
        if (focused) {
            g.color = highlight
            g.fillRoundRect(x + 8, y, width - 16, 28, 7, 7)
        }
        g.color = muted
        g.drawString(name, x + 14, y + 19)
        g.color = text
        val maxValueWidth = width / 3
        var shown = value
        while (shown.length > 1 && g.fontMetrics.stringWidth(shown) > maxValueWidth) shown = shown.dropLast(1)
        if (shown != value) shown += "…"
        g.drawString(shown, x + width - 14 - g.fontMetrics.stringWidth(shown), y + 19)
    }
}
