package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.QuestionOrigin
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

/** 回看只有问题与当时代码；同层刷新保留问题身份，切层才重新选择默认页。 */
internal class QuestionOriginPanel(project: Project) : JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))), AutoCloseable {
    val snapshot = QuestionSnapshotPane(project)
    private val counter = JLabel()
    private val notice = JLabel()
    private var layer: GraphLayerKey? = null
    private var pages = emptyList<QuestionOrigin>()
    private var index = 0
    val previous = popupIconButton(AllIcons.Actions.Back, "上一条提问") { page(-1) }
    val next = popupIconButton(AllIcons.Actions.Forward, "下一条提问") { page(1) }
    private val fonts = CanvasUiFonts(listOf(counter, notice))

    init {
        border = JBUI.Borders.empty(PopupUi.PADDING)
        preferredSize = JBUI.size(520, 420)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(PopupUi.ICON_GAP), 0)).apply {
                isOpaque = false; add(previous); add(next)
            }, BorderLayout.WEST)
            add(counter, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(snapshot, BorderLayout.CENTER)
        add(notice, BorderLayout.SOUTH)
    }

    fun loading(target: GraphLayerKey) {
        if (layer != target) {
            layer = target; pages = emptyList(); index = 0
            snapshot.showSnapshot("", null)
        }
        notice.text = "正在读取本层提问…"
        previous.isEnabled = false; next.isEnabled = false
    }

    fun refresh(target: GraphLayerKey, values: List<QuestionOrigin>, preferredRecordId: String?) {
        val old = pages.getOrNull(index)?.questionId.takeIf { layer == target }
        layer = target
        pages = values
        index = pages.indexOfFirst { it.questionId == old }.takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.answerRecordId == preferredRecordId }.takeIf { it >= 0 } ?: 0
        showPage()
    }

    fun failed(message: String) {
        pages = emptyList(); index = 0
        snapshot.showSnapshot("", null)
        notice.text = "无法读取提问：$message"
        previous.isEnabled = false; next.isEnabled = false
        counter.text = ""
    }

    private fun page(delta: Int) { index = (index + delta).coerceIn(0, (pages.size - 1).coerceAtLeast(0)); showPage() }
    private fun showPage() {
        val page = pages.getOrNull(index)
        snapshot.showSnapshot(page?.question.orEmpty(), page?.anchor)
        counter.text = if (pages.isEmpty()) "" else "${index + 1}/${pages.size}"
        notice.text = if (pages.isEmpty()) "本层暂无提问" else ""
        previous.isEnabled = index > 0
        next.isEnabled = index < pages.lastIndex
    }
    override fun close() { fonts.close(); snapshot.close() }
}
