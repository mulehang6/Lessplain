package com.lessplain.ide

import com.intellij.openapi.editor.Editor
import com.lessplain.model.SourceAnchor
import com.lessplain.ui.CanvasEditorPopup
import com.lessplain.ui.questionEditorTitle
import java.awt.Dimension
import java.awt.Point

/** 一份草稿始终对应一份冻结的选区；关闭弹层不会丢弃未提交文字。 */
internal class SourceSelectionQuestionSession(private val service: UnderstandingService) : AutoCloseable {
    private val drafts = SourceSelectionDrafts()
    private var popup: CanvasEditorPopup? = null
    private var loading: AutoCloseable? = null
    private var generation = 0L
    private var closed = false

    fun show(editor: Editor, binding: SourceNodeBinding, anchor: SourceAnchor) {
        if (service.readOnly || closed) return
        if (editor.project == null) return
        popup?.close()
        val request = ++generation
        loading?.close()
        loading = service.readBackground({
            captureSourceQuestion(
                service.repository,
                service.core,
                binding,
                anchor
            )
        }) { result ->
            if (closed || editor.isDisposed || request != generation) return@readBackground
            result.onFailure { service.contentActions.notifyQuestionCopy(it.message ?: "无法准备问题", true) }
            result.onSuccess { captured ->
                val question = drafts.restoreOrCapture(binding, anchor) { captured }
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
                    saveDispatch = service::readBackground,
                )
            }
        }
    }

    override fun close() {
        closed = true
        generation++
        loading?.close()
        popup?.close()
        drafts.clear()
    }
}
