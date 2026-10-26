package com.lessplain.ui

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

internal enum class TitleActionKind { HOME, BOOK, HISTORY }

/** 工具窗口标题动作使用的小型主题图标。 */
internal class TitleActionIcon(private val kind: TitleActionKind) : Icon {
    override fun getIconWidth(): Int = 20
    override fun getIconHeight(): Int = 20

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.scale(1.25, 1.25)
            g.color = component?.foreground ?: JBColor.foreground()
            g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            when (kind) {
                TitleActionKind.HOME -> {
                    g.draw(Path2D.Double().apply {
                        moveTo(2.0, 7.0); lineTo(8.0, 2.0); lineTo(14.0, 7.0)
                    })
                    g.drawLine(4, 7, 4, 14)
                    g.drawLine(12, 7, 12, 14)
                    g.drawLine(4, 14, 12, 14)
                }
                TitleActionKind.BOOK -> {
                    g.draw(Path2D.Double().apply {
                        moveTo(8.0, 4.0); curveTo(5.0, 2.0, 3.0, 2.0, 1.5, 3.0)
                        lineTo(1.5, 13.0); curveTo(4.0, 12.0, 6.0, 12.5, 8.0, 14.0)
                        curveTo(10.0, 12.5, 12.0, 12.0, 14.5, 13.0)
                        lineTo(14.5, 3.0); curveTo(13.0, 2.0, 11.0, 2.0, 8.0, 4.0)
                    })
                    g.drawLine(8, 4, 8, 14)
                }
                TitleActionKind.HISTORY -> {
                    g.drawOval(2, 2, 12, 12)
                    g.drawLine(8, 4, 8, 8)
                    g.drawLine(8, 8, 11, 10)
                }
            }
        } finally { g.dispose() }
    }
}
