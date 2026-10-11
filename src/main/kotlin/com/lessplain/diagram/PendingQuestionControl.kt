package com.lessplain.diagram

import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.text.HtmlChunk
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.UIManager

/** 单个紧凑图标同时显示复制动作和数量，完整数量保留在悬浮提示与无障碍名称中。 */
internal class PendingQuestionControl {
    val button = DiagramIconButton(PendingQuestionCountIcon(0))
    private var action: (() -> Unit)? = null

    init {
        button.addActionListener { action?.invoke() }
        update(0, null)
    }

    fun update(count: Int, copy: (() -> Unit)?, error: String? = null) {
        action = copy
        button.icon = PendingQuestionCountIcon(count)
        button.isEnabled = count > 0 && copy != null
        val label = "复制提问（$count）"
        button.setToolTipText(HtmlChunk.text(if (error == null) label else "$label：$error"))
        button.accessibleContext.accessibleName = label
    }
}

private class PendingQuestionCountIcon(private val count: Int) : Icon {
    override fun getIconWidth(): Int = 24
    override fun getIconHeight(): Int = 24

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            GraphControlIcon(GraphControlIconKind.COPY).paintIcon(component, g, x, y)
            g.color = UIManager.getColor(if (component?.isEnabled == false) "Label.disabledForeground" else "Label.foreground")
                ?: Color.GRAY
            g.font = (UIManager.getFont("Label.font") ?: Font(Font.DIALOG, Font.PLAIN, 12)).deriveFont(9f)
            val number = if (count > 99) "99+" else count.toString()
            g.drawString(number, x + iconWidth - g.fontMetrics.stringWidth(number), y + iconHeight - 1)
        } finally { g.dispose() }
    }
}
