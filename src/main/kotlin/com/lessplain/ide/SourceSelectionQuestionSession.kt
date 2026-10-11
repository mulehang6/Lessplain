package com.lessplain.ide

import com.intellij.openapi.editor.Editor
import com.lessplain.model.SourceAnchor
import com.lessplain.ui.CanvasEditorPopup
import com.lessplain.ui.SelectedCodePreview
import com.lessplain.ui.questionEditorTitle
import java.awt.Dimension
import java.awt.Point

/** 一份草稿始终对应一份冻结的选区；关闭弹层不会丢弃未提交文字。 */
internal class SourceSelectionQuestionSession(private val service: UnderstandingService) : AutoCloseable {
    private val drafts = SourceSelectionDrafts()
    private var popup: CanvasEditorPopup? = null

    fun show(editor: Editor, binding: SourceNodeBinding, anchor: SourceAnchor) {
        if (service.repository.readOnly) return
        val project = editor.project ?: return
        popup?.close()
        val question = drafts.restoreOrCapture(binding, anchor) {
            captureSourceQuestion(service.repository, service.core, binding, anchor)
        }
        popup = CanvasEditorPopup(
            owner = editor.contentComponent,
            point = Point(0, editor.contentComponent.visibleRect.y),
            size = Dimension(420, 200),
            title = questionEditorTitle(question.binding.label),
            draft = question.draft,
            multiline = true,
            save = { text ->
                question.submit(service.repository, service.core, text)
                service.notifyChanged()
            },
            onClosed = {
                drafts.closed(question)
                popup = null
            },
            selectionPreview = SelectedCodePreview(project, question.anchor),
        )
    }

    override fun close() {
        popup?.close()
        drafts.clear()
    }
}
