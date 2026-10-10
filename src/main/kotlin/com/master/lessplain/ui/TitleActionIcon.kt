package com.master.lessplain.ui

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

internal enum class TitleActionKind { HOME, RECENT, HISTORY }

/** Small theme-aware icons for tool-window title actions. */
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
                TitleActionKind.HISTORY -> {
                    g.drawOval(2, 2, 12, 12)
                    g.drawLine(8, 4, 8, 8)
                    g.drawLine(8, 8, 11, 10)
                }
                TitleActionKind.RECENT -> {
                    g.drawLine(2, 3, 14, 3)
                    g.drawLine(2, 8, 14, 8)
                    g.drawLine(2, 13, 14, 13)
                    g.fillOval(3, 5, 2, 2)
                    g.fillOval(3, 10, 2, 2)
                }
            }
        } finally { g.dispose() }
    }
}
