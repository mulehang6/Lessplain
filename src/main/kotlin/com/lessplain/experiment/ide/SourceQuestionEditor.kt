package com.lessplain.experiment.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.lessplain.experiment.model.EditorSourceBinding
import com.lessplain.experiment.model.JsonPayload
import com.lessplain.experiment.model.ViewNote
import com.lessplain.experiment.model.ViewOrigin
import com.lessplain.experiment.model.ViewOriginEntry
import com.lessplain.experiment.model.SampleLocation
import com.lessplain.model.SourceAnchor
import com.lessplain.ui.CanvasEditorPopup
import com.lessplain.ui.CanvasSaveDispatch
import com.lessplain.ui.CanvasTextDraft
import com.lessplain.ui.SelectedCodePreview
import com.lessplain.ui.questionEditorTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Component
import java.awt.Point
import javax.swing.SwingUtilities

/** 使用打开动作时冻结的选区和 marker binding 保存，不再读取当前编辑器选择。 */
internal fun ViewService.openSourceQuestion(
    editor: Editor,
    anchor: SourceAnchor,
    binding: EditorSourceBinding?,
) {
    check(SwingUtilities.isEventDispatchThread())
    val project = editor.project ?: return
    if (editor.isDisposed) return
    val source = anchor.snapshot()
    val origin = if (binding == null) {
        ViewOrigin(source = source, state = JsonPayload.empty(), entry = ViewOriginEntry.SOURCE_SELECTION)
    } else {
        ViewOrigin(
            viewRef = binding.viewRef,
            elementId = binding.elementId,
            nodeId = binding.nodeId,
            nodeLabel = binding.label.takeIf { binding.nodeId != null },
            source = source,
            state = binding.state,
            entry = ViewOriginEntry.SOURCE_SELECTION,
        )
    }
    openFrozenQuestion(editor, origin, binding?.label ?: "当前源码", SelectedCodePreview(project, anchor), binding)
}

internal fun ViewService.openSampleQuestion(editor: Editor, location: SampleLocation) {
    val origin = location.selection(editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd)
    openFrozenQuestion(editor, origin, location.sample.title, samplePreview(origin))
}

private fun ViewService.samplePreview(origin: ViewOrigin): SelectedCodePreview? = origin.sample?.let {
    SelectedCodePreview(project, text = it.sample.code.substring(it.selectionRange.startOffset, it.selectionRange.endOffset),
        fileName = sampleFileName(it.sample))
}

/** 两类代码共用原生输入和草稿生命周期，保存校验仍由各自来源负责。 */
private fun ViewService.openFrozenQuestion(
    editor: Editor,
    origin: ViewOrigin,
    label: String,
    preview: SelectedCodePreview?,
    binding: EditorSourceBinding? = null,
) {
    check(SwingUtilities.isEventDispatchThread())
    if (editor.isDisposed) return
    val title = questionEditorTitle(label)
    val saveDispatch: CanvasSaveDispatch<Unit> = { save, complete ->
        val job = launch {
            val result = try {
                save()
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            withContext(Dispatchers.EDT) { complete(result) }
        }
        AutoCloseable { job.cancel() }
    }
    val draft = sourceQuestionDrafts.selection(origin)
    CanvasEditorPopup(
        owner = editor.contentComponent,
        point = Point(0, editor.contentComponent.visibleRect.y),
        size = Dimension(420, 200),
        title = title,
        draft = draft,
        multiline = true,
        save = { text -> engine.createNote(text, "question", origin, binding) },
        onClosed = { sourceQuestionDrafts.closeSelection(origin, draft) },
        selectionPreview = preview,
        saveDispatch = saveDispatch,
        onSaved = { launch { changed() } },
    )
}

/** 按备注自身冻结的来源打开原生详情，不读取当前浏览器选区或当前 IDE 编辑器。 */
internal fun ViewService.showNoteDetails(owner: Component, note: ViewNote) {
    check(SwingUtilities.isEventDispatchThread())
    if (!owner.isShowing) return
    val editable = note.status == "pending"
    val preview = note.origin.source
        ?.takeIf { note.origin.entry == ViewOriginEntry.SOURCE_SELECTION || note.origin.entry == null }
        ?.let { SelectedCodePreview(project, it.anchor()) } ?: samplePreview(note.origin)
    val saveDispatch: CanvasSaveDispatch<Unit>? = if (editable) {
        { save, complete ->
            val job = launch {
                val result = try {
                    save()
                    Result.success(Unit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
                withContext(Dispatchers.EDT) { complete(result) }
            }
            AutoCloseable { job.cancel() }
        }
    } else null
    val draft = if (editable) sourceQuestionDrafts.edit(note) else CanvasTextDraft(note.text)
    var subscription: AutoCloseable? = null
    var closed = false
    val popup = CanvasEditorPopup(
        owner = owner,
        point = Point(0, 0),
        size = Dimension(420, 200),
        title = if (note.kind == "feedback") "反馈详情" else "问题详情",
        draft = draft,
        multiline = true,
        save = { text -> if (editable) engine.editNote(note.ref, text) else Unit },
        onClosed = { closed = true; subscription?.close(); sourceQuestionDrafts.closeEdit(note.ref, draft) },
        selectionPreview = preview,
        saveDispatch = saveDispatch,
        onSaved = { launch { changed() } },
        editable = editable,
        delete = if (editable) ({ engine.deleteNote(note.ref) }) else null,
        onDeleted = { launch { changed() } },
        cancelLabel = if (editable) "取消编辑" else "关闭",
    )
    if (editable && !closed) {
        subscription = subscribe { snapshot, _, _, _ ->
            SwingUtilities.invokeLater {
                if (!closed) {
                    sourceQuestionDrafts.retainPending(snapshot)
                    if (snapshot.notes.none { it.ref == note.ref && it.status == "pending" }) popup.lockEditing()
                }
            }
        }
        // 补上打开弹层与订阅之间的窗口，仍只校验冻结的精确引用。
        launch {
            val snapshot = engine.snapshot()
            withContext(Dispatchers.EDT) {
                if (!closed && snapshot.notes.none { it.ref == note.ref && it.status == "pending" }) {
                    sourceQuestionDrafts.retainPending(snapshot)
                    popup.lockEditing()
                }
            }
        }
    }
}
