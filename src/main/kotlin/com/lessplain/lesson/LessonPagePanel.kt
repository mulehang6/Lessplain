package com.lessplain.lesson

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Scrollable

/** 宽窗口限制阅读行长，窄窗口自然回流，页面只使用外层纵向滚动。 */
internal class LessonPagePanel : JPanel(), Scrollable {
    init { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    override fun getInsets(): Insets {
        val side = JBUI.scale(28) + ((width - JBUI.scale(1000)).coerceAtLeast(0) / 2)
        return Insets(JBUI.scale(24), side, JBUI.scale(32), side)
    }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(24)
    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = r.height * 4 / 5
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}
