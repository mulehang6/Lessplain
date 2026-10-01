package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.UIManager

internal enum class GraphControlIconKind { ZOOM_IN, ZOOM_OUT, FIT, ARRANGE, CLUSTERS, HIERARCHY, MORE, PREVIOUS, NEXT }

/** Simple vector glyphs so the controls remain visible in IDE themes and preview rendering. */
internal class GraphControlIcon(private val kind: GraphControlIconKind) : Icon {
    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIManager.getColor("Button.foreground") ?: component?.foreground ?: JBColor.foreground()
            g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (kind) {
                GraphControlIconKind.ZOOM_IN, GraphControlIconKind.ZOOM_OUT -> {
                    g.drawOval(1, 1, 10, 10)
                    g.drawLine(10, 10, 15, 15)
                    g.drawLine(4, 6, 8, 6)
                    if (kind == GraphControlIconKind.ZOOM_IN) g.drawLine(6, 4, 6, 8)
                }
                GraphControlIconKind.FIT -> {
                    g.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawRoundRect(1, 1, 14, 14, 2, 2)
                    g.drawRect(6, 4, 4, 2)
                    g.drawLine(8, 6, 8, 8)
                    g.drawLine(4, 8, 12, 8)
                    g.drawLine(4, 8, 4, 10)
                    g.drawLine(12, 8, 12, 10)
                    g.drawRect(3, 10, 3, 2)
                    g.drawRect(10, 10, 3, 2)
                }
                GraphControlIconKind.CLUSTERS -> {
                    g.drawRoundRect(1, 2, 14, 4, 2, 2)
                    g.drawRoundRect(1, 10, 14, 4, 2, 2)
                }
                GraphControlIconKind.ARRANGE -> {
                    g.stroke = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawRoundRect(6, 1, 4, 4, 1, 1)
                    g.drawLine(8, 5, 8, 8)
                    g.drawLine(4, 8, 12, 8)
                    g.drawLine(4, 8, 4, 11)
                    g.drawLine(12, 8, 12, 11)
                    g.drawRoundRect(2, 11, 4, 4, 1, 1)
                    g.drawRoundRect(10, 11, 4, 4, 1, 1)
                }
                GraphControlIconKind.HIERARCHY -> {
                    g.drawLine(3, 2, 3, 13)
                    g.drawLine(3, 5, 8, 5)
                    g.drawLine(3, 12, 8, 12)
                    g.fillOval(9, 3, 4, 4)
                    g.fillOval(9, 10, 4, 4)
                }
                GraphControlIconKind.MORE -> {
                    g.fillOval(2, 7, 3, 3)
                    g.fillOval(7, 7, 3, 3)
                    g.fillOval(12, 7, 3, 3)
                }
                GraphControlIconKind.PREVIOUS, GraphControlIconKind.NEXT -> {
                    val direction = if (kind == GraphControlIconKind.PREVIOUS) -1 else 1
                    val center = 8
                    g.drawLine(center - 5 * direction, 8, center + 5 * direction, 8)
                    g.drawLine(center + 5 * direction, 8, center * 1 + direction, 4)
                    g.drawLine(center + 5 * direction, 8, center * 1 + direction, 12)
                }
            }
        } finally {
            g.dispose()
        }
    }
}
