package com.master.understanding.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.master.understanding.ui.DeleteRecordState
import com.master.understanding.ui.UnderstandingUiMetrics
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/** Uses the IDE's dialog decorations and actions; cancellation is the initial and default action. */
internal class DeleteRecordDialog(
    project: Project,
    private val state: DeleteRecordState,
    private val confirm: () -> Unit,
    private val cancel: () -> Unit,
    closed: () -> Unit,
) : DialogWrapper(project), DeleteRecordWindow {
    init {
        title = "删除历史记录"
        setOKButtonText("删除")
        setCancelButtonText("取消")
        okAction.putValue(DEFAULT_ACTION, null)
        cancelAction.putValue(DEFAULT_ACTION, true)
        cancelAction.putValue(FOCUSED_ACTION, true)
        init()
        Disposer.register(disposable) { closed() }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(UnderstandingUiMetrics.SECTION_GAP))).apply {
        border = JBUI.Borders.empty(UnderstandingUiMetrics.SMALL_GAP, 0)
        add(JTextArea(state.title).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            columns = 42
            rows = 2
            font = JBFont.regular()
            accessibleContext.accessibleName = "将删除的理解图"
        }, BorderLayout.CENTER)
        add(JBLabel("永久删除这条理解图及其全部追问？此操作无法撤销。"), BorderLayout.SOUTH)
    }

    override fun doOKAction() {
        if (isDisposed || state.busy) return
        setErrorText(null)
        confirm()
        if (!isDisposed) setErrorText(state.error?.let(StringUtil::escapeXmlEntities))
    }

    override fun doCancelAction() {
        cancel()
        if (!isDisposed) super.doCancelAction()
    }

    override fun close() {
        if (!isDisposed) close(CANCEL_EXIT_CODE)
    }
}
