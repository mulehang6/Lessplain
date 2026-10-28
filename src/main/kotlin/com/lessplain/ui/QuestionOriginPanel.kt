package com.lessplain.ui

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.lessplain.ide.QuestionOrigin
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

/** 每次只读一个明确的问题；节点注释的分页不属于此组件。 */
internal class QuestionOriginPanel(project: Project) : JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))), AutoCloseable {
    val snapshot = QuestionSnapshotPane(project)
    private val notice = JLabel()
    private val fonts = CanvasUiFonts(listOf(notice))
    init {
        border = JBUI.Borders.empty(PopupUi.PADDING)
        preferredSize = JBUI.size(520, 420)
        add(snapshot, BorderLayout.CENTER); add(notice, BorderLayout.SOUTH)
    }
    fun loading() { snapshot.showSnapshot("", null); notice.text = "正在读取提问…" }
    fun refresh(origin: QuestionOrigin) { snapshot.showSnapshot(origin.question, origin.anchor); notice.text = "" }
    fun failed(message: String) { snapshot.showSnapshot("", null); notice.text = "无法读取提问：$message" }
    override fun close() { fonts.close(); snapshot.close() }
}
