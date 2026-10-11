package com.master.lessplain.ide

import com.master.lessplain.diagram.UnderstandingDiagram
import com.master.lessplain.diagram.noteActionAt
import com.master.lessplain.diagram.notePopupAnchor
import com.master.lessplain.model.UnderstandingQuestion
import com.master.lessplain.model.UnderstandingRecord
import com.master.lessplain.storage.cancelPendingQuestion
import com.master.lessplain.storage.pendingQuestionsForNode
import com.master.lessplain.ui.CanvasTextDraft
import com.master.lessplain.ui.CanvasEditorPopup
import com.master.lessplain.ui.NodeNoteContent
import com.master.lessplain.ui.NodeNoteContentState
import com.master.lessplain.ui.NodeNoteDraft
import com.master.lessplain.ui.ThemedCanvasPopup
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

/** 在单个画布生命周期内管理已打开注释和未保存草稿。 */
internal class CanvasContentSession(
    private val service: UnderstandingService,
    private val owner: Component,
    private val onDispose: () -> Unit,
    private val addNote: (UnderstandingRecord, String, Component, Point) -> Unit,
    private val editNote: (UnderstandingRecord, String, UnderstandingQuestion, Component, Point) -> Unit,
) : AutoCloseable {
    private var editor: CanvasEditorPopup? = null
    private var editorKey: String? = null
    private var preview: ThemedCanvasPopup? = null
    private val selection = ClickedNotePopup()
    private var target: Pair<UnderstandingRecord, String>? = null
    private var point = Point()
    private var disposed = false
    private val drafts = CanvasContentDrafts()
    private val draftListeners = linkedSetOf<(Set<Pair<String, String>>) -> Unit>()
    private val content = NodeNoteContentState()
    private val changed = service.onChanged { refresh() }
    private val visibility = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !owner.isShowing) {
            closePreview(); editor?.close()
        }
    }

    init { (owner as? UnderstandingDiagram)?.overlays?.own(this); owner.addHierarchyListener(visibility) }

    fun show(record: UnderstandingRecord, nodeId: String, at: Point) {
        if (disposed) return
        val notes = service.repository.pendingQuestionsForNode(record.id, nodeId)
        val nodeDrafts = drafts.forNode(record.id, nodeId)
        if (notes.isEmpty() && nodeDrafts.isNotEmpty()) {
            selection.close()
            closePreview()
            target = record to nodeId
            point = (owner as? UnderstandingDiagram)?.notePopupAnchor(at) ?: Point(at)
            val draft = nodeDrafts.first()
            if (draft.questionId.isEmpty()) {
                addNote(record, nodeId, owner, point)
            } else {
                val question = runCatching { service.repository.getQuestion(draft.questionId) }.getOrNull()
                if (question?.status == "pending") {
                    editNote(record, nodeId, question, owner, point)
                } else if (drafts.remove(draft.key)) {
                    notifyDraftNodesChanged()
                }
            }
            return
        }
        val open = selection.click(record.id, nodeId)
        closePreview(clearSelection = false)
        if (!open) return
        editor?.close()
        target = record to nodeId
        point = (owner as? UnderstandingDiagram)?.notePopupAnchor(at) ?: Point(at)
        if (preview == null) openPreview()
        preview?.requestFocus()
    }

    private fun openPreview() {
        if (disposed || preview != null || editor != null) return
        val (record, nodeId) = target ?: return
        refresh()
        if (content.notes.isEmpty() && content.drafts.isEmpty()) return
        val generation = selection.generation
        preview = ThemedCanvasPopup(owner, point, Dimension(430, 300), requestFocus = true,
            onClosed = { if (selection.close(generation)) preview = null }, mayCancel = { mayDismissNotes() }) {
            NodeNoteContent(content, service.repository.readOnly,
                add = { closePreview(); addNote(record, nodeId, owner, point) },
                edit = { note -> closePreview(); editNote(record, nodeId, note, owner, point) },
                continueDraft = { questionId ->
                    closePreview()
                    if (questionId.isEmpty()) {
                        addNote(record, nodeId, owner, point)
                    } else {
                        content.notes.firstOrNull { it.id == questionId }?.let { note ->
                            editNote(record, nodeId, note, owner, point)
                        }
                    }
                },
                delete = { note ->
                    runCatching { service.repository.cancelPendingQuestion(note.id) }.onSuccess {
                        if (drafts.remove(noteKey(record.id, nodeId, note.id))) notifyDraftNodesChanged()
                        service.notifyChanged()
                    }.onFailure { content.error = it.message }
                })
        }
    }

    fun onDraftNotesChanged(listener: (Set<Pair<String, String>>) -> Unit): AutoCloseable {
        if (disposed) {
            listener(emptySet())
            return AutoCloseable { }
        }
        draftListeners += listener
        listener(drafts.nodeKeys())
        return AutoCloseable { draftListeners -= listener }
    }

    private fun refresh() {
        if (disposed) return
        val removed = drafts.removeNoteDrafts { draft ->
            draft.questionId.isNotEmpty() && runCatching {
                service.repository.getQuestion(draft.questionId).status != "pending"
            }.getOrDefault(true)
        }
        if (removed) notifyDraftNodesChanged()
        target?.let { (record, nodeId) ->
            content.notes = service.repository.pendingQuestionsForNode(record.id, nodeId)
            content.drafts = drafts.forNode(record.id, nodeId).map { NodeNoteDraft(it.questionId, it.text) }
            if (content.notes.isEmpty() && content.drafts.isEmpty()) closePreview()
        }
        editorKey?.takeUnless(::mayRetain)?.let { editor?.close() }
    }

    fun edit(key: String, title: String, initial: String, at: Point, multiline: Boolean, save: (String) -> Unit) {
        if (disposed) return
        editor?.close()
        val draft = CanvasTextDraft(drafts.text(key) ?: initial)
        editorKey = key
        editor = CanvasEditorPopup(owner, at, Dimension(400, if (multiline) 290 else 150), title, draft, multiline,
            save = save, onClosed = {
                val retained = !disposed && draft.retain && mayRetain(key)
                val changed = if (retained) drafts.retain(key, draft.text) else drafts.remove(key)
                editor = null
                editorKey = null
                if (key.startsWith("note:") && changed && !disposed) notifyDraftNodesChanged()
                if (!draft.retain) service.notifyChanged()
            })
    }

    private fun notifyDraftNodesChanged() {
        if (disposed) return
        val snapshot = drafts.nodeKeys()
        draftListeners.toList().forEach { it(snapshot) }
    }

    /** 平台关闭弹层前，先让画布气泡完成自己的切换操作。 */
    private fun mayDismissNotes(): Boolean {
        val event = java.awt.EventQueue.getCurrentEvent() as? java.awt.event.MouseEvent ?: return true
        if (event.id != java.awt.event.MouseEvent.MOUSE_PRESSED || owner !is UnderstandingDiagram) return true
        val at = runCatching { javax.swing.SwingUtilities.convertPoint(event.component, event.point, owner) }.getOrNull()
            ?: return true
        return owner.noteActionAt(at) == null
    }

    private fun mayRetain(key: String): Boolean {
        val recordId = key.substringAfter(':').substringBefore(':')
        if (runCatching { service.repository.getRecord(recordId) }.isFailure) return false
        if (!key.startsWith("note:")) return true
        val questionId = key.substringAfterLast(':')
        return questionId.isEmpty() || runCatching { service.repository.getQuestion(questionId).status == "pending" }
            .getOrDefault(false)
    }

    private fun closePreview(clearSelection: Boolean = true) {
        if (clearSelection) selection.close()
        val current = preview
        preview = null
        current?.close()
    }

    override fun close() {
        if (disposed) return
        disposed = true
        closePreview(); editor?.close(); editor = null
        owner.removeHierarchyListener(visibility)
        changed.close(); drafts.clear(); draftListeners.clear(); target = null
        onDispose()
    }
}

internal fun noteKey(recordId: String, nodeId: String, questionId: String) = "note:$recordId:$nodeId:$questionId"

internal data class CanvasNoteDraftEntry(
    val key: String,
    val recordId: String,
    val nodeId: String,
    val questionId: String,
    val text: String,
)

/** 画布草稿与仓库存储状态、已保存待答注释计数分开。 */
internal class CanvasContentDrafts {
    private val values = linkedMapOf<String, String>()

    fun text(key: String): String? = values[key]

    fun retain(key: String, text: String): Boolean {
        val previous = values[key]
        if (key.startsWith("note:") && text.isBlank()) values.remove(key) else values[key] = text
        return previous != values[key]
    }

    fun remove(key: String): Boolean = values.remove(key) != null

    fun forNode(recordId: String, nodeId: String): List<CanvasNoteDraftEntry> = noteDrafts()
        .filter { it.recordId == recordId && it.nodeId == nodeId }

    fun nodeKeys(): Set<Pair<String, String>> = noteDrafts()
        .mapTo(linkedSetOf()) { it.recordId to it.nodeId }

    fun removeNoteDrafts(shouldRemove: (CanvasNoteDraftEntry) -> Boolean): Boolean {
        val keys = noteDrafts().filter(shouldRemove).map { it.key }
        keys.forEach { values.remove(it) }
        return keys.isNotEmpty()
    }

    fun clear() = values.clear()

    private fun noteDrafts(): List<CanvasNoteDraftEntry> = values.entries.mapNotNull { (key, text) ->
        if (text.isBlank() || !key.startsWith("note:")) return@mapNotNull null
        val firstSeparator = key.indexOf(':', "note:".length)
        val lastSeparator = key.lastIndexOf(':')
        if (firstSeparator <= "note:".lastIndex || lastSeparator <= firstSeparator) return@mapNotNull null
        val recordId = key.substring("note:".length, firstSeparator)
        val nodeId = key.substring(firstSeparator + 1, lastSeparator)
        if (recordId.isEmpty() || nodeId.isEmpty()) return@mapNotNull null
        CanvasNoteDraftEntry(key, recordId, nodeId, key.substring(lastSeparator + 1), text)
    }
}
