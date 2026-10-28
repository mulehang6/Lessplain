package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

/** 固定图标尺寸的居中分页，不以文字按钮挤占历史列表。 */
internal class HistoryPagingPanel : JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(PopupUi.GAP), 0)) {
    private var previous: () -> Unit = {}
    private var next: () -> Unit = {}
    val previousButton = popupIconButton(AllIcons.Actions.Back, "上一页") { previous() }
    val nextButton = popupIconButton(AllIcons.Actions.Forward, "下一页") { next() }
    val counter = JLabel()
    init { isOpaque = false; add(previousButton); add(counter); add(nextButton) }
    fun update(page: Int, hasNext: Boolean, back: () -> Unit, forward: () -> Unit) {
        previous = back; next = forward
        counter.text = "第 ${page + 1} 页"
        previousButton.isEnabled = page > 0; nextButton.isEnabled = hasNext
    }
}
