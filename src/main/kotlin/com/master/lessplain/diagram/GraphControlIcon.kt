package com.master.lessplain.diagram

import com.master.lessplain.ui.UnderstandingUiStyle
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

internal enum class GraphControlIconKind {
    ZOOM_IN, ZOOM_OUT, FIT, ARRANGE, CLUSTERS, HIERARCHY, COMPLETE_ROUTE, CANCEL_ROUTE, MORE, PREVIOUS, NEXT,
    COPY, COPIED, QUESTIONS, NOTE, ADD,
}

/** Simple vector glyphs so the controls remain visible in IDE themes and preview rendering. */
internal class GraphControlIcon(private val kind: GraphControlIconKind) : Icon {
    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = if (component?.isEnabled == false) UnderstandingUiStyle.disabledForeground
                else UnderstandingUiStyle.controlForeground
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
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
                GraphControlIconKind.COMPLETE_ROUTE -> {
                    g.drawOval(1, 1, 3, 3)
                    g.drawOval(1, 12, 3, 3)
                    g.drawLine(3, 4, 3, 12)
                    g.drawLine(3, 9, 9, 3)
                    g.drawLine(9, 3, 12, 3)
                    g.drawLine(10, 11, 15, 11)
                    g.drawLine(13, 8, 13, 14)
                }
                GraphControlIconKind.CANCEL_ROUTE -> {
                    g.drawLine(3, 3, 13, 13)
                    g.drawLine(13, 3, 3, 13)
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
                GraphControlIconKind.COPY -> {
                    g.drawRoundRect(2, 1, 9, 10, 2, 2)
                    g.drawRoundRect(5, 5, 9, 10, 2, 2)
                }
                GraphControlIconKind.COPIED -> {
                    g.drawLine(2, 8, 6, 12)
                    g.drawLine(6, 12, 14, 3)
                }
                GraphControlIconKind.QUESTIONS, GraphControlIconKind.NOTE -> {
                    g.drawRoundRect(1, 2, 14, 10, 4, 4)
                    g.drawLine(5, 12, 5, 15)
                    g.drawLine(5, 15, 9, 12)
                    if (kind == GraphControlIconKind.QUESTIONS) {
                        g.drawArc(6, 4, 4, 4, 0, 180)
                        g.drawLine(10, 6, 8, 8)
                        g.fillOval(7, 9, 2, 2)
                    } else {
                        g.drawLine(4, 6, 12, 6)
                        g.drawLine(4, 9, 9, 9)
                    }
                }
                GraphControlIconKind.ADD -> {
                    g.drawLine(3, 8, 13, 8)
                    g.drawLine(8, 3, 8, 13)
                }
            }
        } finally {
            g.dispose()
        }
    }
}
