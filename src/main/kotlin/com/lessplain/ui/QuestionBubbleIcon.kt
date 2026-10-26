package com.lessplain.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

/** 追问使用主题强调色；禁用滤色和悬停反馈仍交给原生按钮。 */
internal object QuestionBubbleIcon : Icon {
    override fun getIconWidth() = JBUI.scale(16)
    override fun getIconHeight() = JBUI.scale(16)
    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            val scale = iconWidth / 16.0
            g.scale(scale, scale)
            g.color = UnderstandingUiStyle.accent
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.draw(Path2D.Double().apply {
                moveTo(5.0, 12.5); lineTo(2.5, 14.0); lineTo(3.0, 10.5)
                curveTo(-1.0, 3.0, 5.0, 0.0, 10.0, 2.0)
                curveTo(17.0, 4.5, 14.0, 14.0, 5.0, 12.5)
            })
            g.draw(Path2D.Double().apply {
                moveTo(6.0, 5.0); curveTo(6.0, 3.0, 10.5, 3.0, 10.0, 5.5)
                curveTo(9.5, 7.0, 8.0, 6.5, 8.0, 8.0)
            })
            g.fillOval(7, 10, 2, 2)
        } finally { g.dispose() }
    }
}
