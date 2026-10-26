package com.lessplain.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/** 教程里的图、模拟和代码区域共用主题边界；子组件与命中一起限制在圆角内。 */
internal open class RoundedPanel(layout: LayoutManager = BorderLayout()) : JPanel(layout) {
    init { isOpaque = false; border = JBUI.Borders.empty(1) }
    private fun outline() = RoundRectangle2D.Double(0.5, 0.5, (width - 1).coerceAtLeast(0).toDouble(),
        (height - 1).coerceAtLeast(0).toDouble(), JBUI.scale(12).toDouble(), JBUI.scale(12).toDouble())
    override fun contains(x: Int, y: Int) = outline().contains(x.toDouble(), y.toDouble())
    override fun paintChildren(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try { g.clip(outline()); super.paintChildren(g) } finally { g.dispose() }
    }
    override fun paintBorder(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UnderstandingUiStyle.border
            g.draw(outline())
        } finally { g.dispose() }
    }
}
