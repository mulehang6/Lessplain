package com.lessplain.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.SwingConstants
import javax.swing.border.AbstractBorder
import javax.swing.plaf.basic.BasicButtonUI

/** 代码和底部展开入口共用一个安静的圆角边框，留出内边距保护代码区域。 */
internal class CodePreviewBorder : AbstractBorder() {
    override fun getBorderInsets(component: Component): Insets = JBUI.insets(8)

    override fun getBorderInsets(component: Component, insets: Insets): Insets {
        val padding = JBUI.scale(8)
        insets.set(padding, padding, padding, padding)
        return insets
    }

    override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g = graphics.create() as Graphics2D
        try {
            val stroke = JBUI.scale(1).toFloat()
            val half = stroke / 2
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = BasicStroke(stroke)
            g.color = UnderstandingUiStyle.border
            g.draw(RoundRectangle2D.Float(x + half, y + half, (width - stroke).coerceAtLeast(0f),
                (height - stroke).coerceAtLeast(0f), UnderstandingUiStyle.NODE_ARC.toFloat(),
                UnderstandingUiStyle.NODE_ARC.toFloat()))
        } finally { g.dispose() }
    }
}

/** 保留标准按钮的键盘与辅助功能，将展开呈现为代码块内部的轻量入口。 */
internal class CodePreviewToggle(expanded: () -> Boolean) : JButton("展开") {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        isDefaultCapable = false
        border = JBUI.Borders.empty(1, 6)
        horizontalTextPosition = SwingConstants.LEFT
        iconTextGap = JBUI.scale(6)
        icon = PreviewChevron(expanded)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = "展开代码片段"
    }

    /** 避免主题按钮的最小尺寸把单行展开入口撑成普通操作按钮。 */
    override fun updateUI() { setUI(BasicButtonUI()) }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        val padding = insets
        return Dimension(
            metrics.stringWidth(text) + icon.iconWidth + iconTextGap + padding.left + padding.right,
            maxOf(metrics.height, icon.iconHeight) + padding.top + padding.bottom,
        )
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (model.isRollover || model.isPressed) {
                g.color = if (model.isPressed) UnderstandingUiStyle.controlPressed else UnderstandingUiStyle.controlHover
                g.fillRoundRect(0, 0, width, height, UnderstandingUiStyle.CONTROL_ARC, UnderstandingUiStyle.CONTROL_ARC)
            }
            if (hasFocus()) {
                g.color = UnderstandingUiStyle.accent
                g.drawLine(insets.left, height - 1, width - insets.right - 1, height - 1)
            }
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }
}

private class PreviewChevron(private val expanded: () -> Boolean) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(10)
    override fun getIconHeight(): Int = JBUI.scale(10)

    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = component.foreground
            g.stroke = BasicStroke(JBUI.scale(1).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val near = y + JBUI.scale(if (expanded()) 6 else 3)
            val far = y + JBUI.scale(if (expanded()) 3 else 6)
            val middle = x + iconWidth / 2
            g.drawLine(x + JBUI.scale(1), near, middle, far)
            g.drawLine(middle, far, x + iconWidth - JBUI.scale(1), near)
        } finally { g.dispose() }
    }
}
