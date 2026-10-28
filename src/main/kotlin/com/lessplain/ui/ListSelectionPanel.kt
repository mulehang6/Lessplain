package com.lessplain.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.JPanel

/** 使用标准 Swing 绘制主题选中背景，避免依赖平台实验性 SelectablePanel。 */
internal class ListSelectionPanel(background: Color, private val selected: Color?,
    private val selectionInsets: Insets = JBUI.insets(2, 4)) : JPanel() {
    init { this.background = background }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val color = selected ?: return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = color
            val arc = JBUI.CurrentTheme.Popup.Selection.ARC.get()
            g.fillRoundRect(selectionInsets.left, selectionInsets.top,
                (width - selectionInsets.left - selectionInsets.right).coerceAtLeast(0),
                (height - selectionInsets.top - selectionInsets.bottom).coerceAtLeast(0), arc, arc)
        } finally { g.dispose() }
    }
}
