package com.lessplain.ide

import com.intellij.openapi.editor.Editor
import com.lessplain.model.SourceAnchor
import com.lessplain.ui.CanvasEditorPopup
import com.lessplain.ui.questionEditorTitle
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

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
        val selection = uiRead {
            val model = editor.selectionModel
            editor.offsetToXY(model.selectionStart) to editor.offsetToXY((model.selectionEnd - 1).coerceAtLeast(model.selectionStart))
        }
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
            if (closed || editor.isDisposed || !editor.contentComponent.isShowing || request != generation) return@readBackground
            result.onFailure { service.contentActions.notifyQuestionCopy(it.message ?: "无法准备问题", true) }
            result.onSuccess { captured ->
                val question = drafts.restoreOrCapture(binding, anchor) { captured }
                val area = uiRead { selectedCodePopupAnchor(selection.first, selection.second, editor.lineHeight, editor.contentComponent.visibleRect) }
                popup = CanvasEditorPopup(
                    owner = editor.contentComponent,
                    point = Point(area.x, area.y + area.height),
                    anchorBounds = area,
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

/** 以触发时的选区定位，裁到当前可见区域；多行和折行均使用编辑器的真实显示坐标。 */
internal fun selectedCodePopupAnchor(first: Point, last: Point, lineHeight: Int, viewport: Rectangle): Rectangle {
    val right = viewport.x + viewport.width.coerceAtLeast(1)
    val bottom = viewport.y + viewport.height.coerceAtLeast(1)
    val x = first.x.coerceIn(viewport.x, right - 1)
    val top = first.y.coerceIn(viewport.y, bottom - 1)
    val end = (last.y + lineHeight.coerceAtLeast(1)).coerceIn(top + 1, bottom)
    return Rectangle(x, top, 1, end - top)
}
