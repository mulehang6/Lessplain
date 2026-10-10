package com.master.lessplain.diagram

import com.master.lessplain.ui.UnderstandingControlPainter
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.geom.Rectangle2D
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton

/** Keeps native Swing button input and accessibility while using the graph's theme-aware control style. */
internal class DiagramIconButton(icon: Icon, private val quiet: Boolean = false) : JButton(icon) {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        border = BorderFactory.createEmptyBorder()
        margin = Insets(0, 0, 0, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            UnderstandingControlPainter.paintBackground(g,
                Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()),
                isEnabled, model.isRollover, model.isPressed && model.isArmed, hasFocus(), quiet)
            icon?.let { it.paintIcon(this, g, (width - it.iconWidth) / 2, (height - it.iconHeight) / 2) }
        } finally {
            g.dispose()
        }
    }
}
