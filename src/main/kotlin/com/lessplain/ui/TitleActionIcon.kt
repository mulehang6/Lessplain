package com.lessplain.ui

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

internal enum class TitleActionKind { BOOK, HISTORY }

/** 工具窗口标题动作使用的小型主题图标。 */
internal class TitleActionIcon(private val kind: TitleActionKind, private val unread: Boolean = false) : Icon {
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
            if (unread) { g.color = UnderstandingUiStyle.accent; g.fillOval(11, 0, 5, 5); g.color = component?.foreground ?: JBColor.foreground() }
            when (kind) {
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

/** 菜单与记录条目只显示有无未读，不展示数量。 */
internal object UnreadDotIcon : Icon {
    override fun getIconWidth() = com.intellij.util.ui.JBUI.scale(12)
    override fun getIconHeight() = com.intellij.util.ui.JBUI.scale(12)
    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UnderstandingUiStyle.accent
            val inset = com.intellij.util.ui.JBUI.scale(3)
            g.fillOval(x + inset, y + inset, iconWidth - inset * 2, iconHeight - inset * 2)
        } finally { g.dispose() }
    }
}
