package com.lessplain.ide

import com.lessplain.core.LatestBackgroundRead
import com.lessplain.core.cancellationAwareResult
import com.lessplain.diagram.UnderstandingDiagram
import com.lessplain.diagram.noteActionAt
import com.lessplain.diagram.notePopupAnchor
import com.lessplain.model.UnderstandingQuestion
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.cancelPendingQuestion
import com.lessplain.storage.pendingQuestionsForNode
import com.lessplain.ui.*
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

/** 弹层属于当前组件；草稿由逻辑画布宿主保留，修订重建组件不会丢失。 */
internal class CanvasContentSession(
    private val service: UnderstandingService,
    private val owner: Component,
    private val onDispose: () -> Unit,
    private val addNote: (UnderstandingRecord, String, Component, Point) -> Unit,
    private val editNote: (UnderstandingRecord, String, UnderstandingQuestion, Component, Point) -> Unit,
    private val drafts: CanvasContentDrafts,
) : AutoCloseable {
    private var editor: CanvasEditorPopup? = null
    private var editorKey: String? = null
    private var preview: ThemedCanvasPopup? = null
    private val selection = ClickedNotePopup()
    private var target: Pair<UnderstandingRecord, String>? = null
    private var point = Point()
    private var disposed = false
    private val content = NodeNoteContentState()

    private data class Snapshot(
        val notes: List<UnderstandingQuestion>,
        val pendingIds: Set<String>,
        val existingNodes: Set<Pair<String, String>>,
        val checkedDrafts: Set<String>,
    )

    private var pendingIds = emptySet<String>()
    private var openWhenLoaded = false
    private var preparation: AutoCloseable? = null
    private var preparationGeneration = 0L
    private val loader = LatestBackgroundRead<Snapshot>(service::readBackground) { result ->
        result.onSuccess { loaded ->
            pendingIds = loaded.pendingIds
            if (drafts.removeNoteDrafts {
                    it.key in loaded.checkedDrafts && ((it.recordId to it.nodeId) !in loaded.existingNodes ||
                            (it.questionId.isNotEmpty() && it.questionId !in pendingIds))
                }) notifyDraftNodesChanged()
            content.notes = loaded.notes
            target?.let { (record, nodeId) ->
                content.drafts = drafts.forNode(record.id, nodeId).map { NodeNoteDraft(it.questionId, it.text) }
            }
            editorKey?.takeUnless(::mayRetain)?.let { editor?.close() }
            if (openWhenLoaded) {
                openWhenLoaded = false; openLoadedPreview()
            } else if (content.notes.isEmpty() && content.drafts.isEmpty()) closePreview()
        }.onFailure { content.error = it.message }
    }
    private val changed = service.onChanged { refresh() }
    private val visibility = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !owner.isShowing) {
            openWhenLoaded = false
            preparationGeneration++
            preparation?.close()
            closePreview(); editor?.close()
        }
    }

    init {
        (owner as? UnderstandingDiagram)?.overlays?.own(this); owner.addHierarchyListener(visibility)
    }

    fun show(record: UnderstandingRecord, nodeId: String, at: Point) {
        if (disposed) return
        val open = selection.click(record.id, nodeId)
        closePreview(clearSelection = false)
        if (!open) {
            openWhenLoaded = false; return
        }
        editor?.close()
        target = record to nodeId
        point = (owner as? UnderstandingDiagram)?.notePopupAnchor(at) ?: Point(at)
        openWhenLoaded = true
        refresh()
    }

    private fun openLoadedPreview() {
        if (disposed || !owner.isShowing) return
        val (record, nodeId) = target ?: return
        val notes = content.notes
        val nodeDrafts = drafts.forNode(record.id, nodeId)
        if (notes.isEmpty() && nodeDrafts.isNotEmpty()) {
            selection.close()
            closePreview()
            target = record to nodeId
            val draft = nodeDrafts.first()
            if (draft.questionId.isEmpty()) {
                addNote(record, nodeId, owner, point)
            } else {
                val question = notes.firstOrNull { it.id == draft.questionId }
                if (question?.status == "pending") {
                    editNote(record, nodeId, question, owner, point)
                } else if (drafts.remove(draft.key)) {
                    notifyDraftNodesChanged()
                }
            }
            return
        }
        if (preview == null) openPreview()
        preview?.requestFocus()
    }

    private fun openPreview() {
        if (disposed || preview != null || editor != null) return
        val (record, nodeId) = target ?: return
        if (content.notes.isEmpty() && content.drafts.isEmpty()) return
        val generation = selection.generation
        preview = ThemedCanvasPopup(
            owner, point, Dimension(430, 300), requestFocus = true,
            onClosed = { if (selection.close(generation)) preview = null }, mayCancel = { mayDismissNotes() }) {
            NodeNoteContent(
                content, service.readOnly,
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
                    service.readBackground({ service.repository.cancelPendingQuestion(note.id) }) { result ->
                        result.onSuccess {
                            if (drafts.remove(noteKey(record.id, nodeId, note.id))) notifyDraftNodesChanged()
                            service.notifyChanged()
                        }.onFailure { content.error = it.message }
                    }
                })
        }
    }

    fun onDraftNotesChanged(listener: (Set<Pair<String, String>>) -> Unit): AutoCloseable {
        if (disposed) {
            listener(emptySet())
            return AutoCloseable { }
        }
        return drafts.listen(listener)
    }

    /** 弹层的准备任务属于画布，切换或关闭画布不能在完成后再打开旧弹层。 */
    fun <T> prepare(read: () -> T, ready: (T) -> Unit) {
        if (disposed) return
        val request = ++preparationGeneration
        preparation?.close()
        preparation = service.readBackground(read) { result ->
            if (!disposed && request == preparationGeneration && owner.isShowing) {
                result.onSuccess(ready)
                    .onFailure { service.contentActions.notifyQuestionCopy(it.message ?: "无法读取内容", true) }
            }
        }
    }

    private fun refresh() {
        if (disposed) return
        val fixedTarget = target
        val fixedDrafts = drafts.noteDrafts()
        val fixedEditorKey = editorKey
        loader.request {
            val notes =
                fixedTarget?.let { (record, nodeId) -> service.repository.pendingQuestionsForNode(record.id, nodeId) }
                    .orEmpty()
            val pending = notes.mapTo(mutableSetOf()) { it.id }
            fixedDrafts.filter { it.questionId.isNotEmpty() }.forEach { draft ->
                cancellationAwareResult { service.repository.getQuestion(draft.questionId) }.getOrNull()
                    ?.takeIf { it.status == "pending" }?.let { pending += it.id }
            }
            val ids = fixedDrafts.map { it.recordId } + listOfNotNull(fixedTarget?.first?.id) +
                    listOfNotNull(fixedEditorKey?.substringAfter(':')?.substringBefore(':'))
            val existingNodes = ids.distinct().flatMap { id ->
                cancellationAwareResult { service.storage.loadRecord(id) }.getOrNull()?.graph?.nodes.orEmpty()
                    .map { id to it.id }
            }.toSet()
            Snapshot(notes, pending, existingNodes, fixedDrafts.mapTo(mutableSetOf()) { it.key })
        }
    }

    fun edit(
        key: String, title: String, initial: String, at: Point, multiline: Boolean,
        selectionPreview: SelectedCodePreview? = null, afterSave: () -> Unit = {}, save: (String) -> Unit
    ) {
        if (disposed) return
        editor?.close()
        val draft = CanvasTextDraft(drafts.text(key) ?: initial)
        if (key.startsWith("note:")) key.substringAfterLast(':').takeIf { it.isNotEmpty() }?.let { pendingIds += it }
        editorKey = key
        editor = CanvasEditorPopup(
            owner, at, Dimension(400, if (multiline) 290 else 150), title, draft, multiline,
            save = save, saveDispatch = service::readBackground, onSaved = afterSave,
            selectionPreview = selectionPreview, onClosed = {
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
        drafts.notifyListeners()
    }

    /** 平台关闭弹层前，先让画布气泡完成自己的切换操作。 */
    private fun mayDismissNotes(): Boolean {
        val event = java.awt.EventQueue.getCurrentEvent() as? java.awt.event.MouseEvent ?: return true
        if (event.id != java.awt.event.MouseEvent.MOUSE_PRESSED || owner !is UnderstandingDiagram) return true
        val at =
            runCatching { javax.swing.SwingUtilities.convertPoint(event.component, event.point, owner) }.getOrNull()
                ?: return true
        return owner.noteActionAt(at) == null
    }

    private fun mayRetain(key: String): Boolean {
        val recordId = key.substringAfter(':').substringBefore(':')
        if (service.storage.record(recordId) == null) return false
        if (!key.startsWith("note:")) return true
        val questionId = key.substringAfterLast(':')
        return questionId.isEmpty() || questionId in pendingIds
    }

    private fun closePreview(clearSelection: Boolean = true) {
        if (clearSelection) selection.close()
        val current = preview
        preview = null
        current?.close()
    }

    override fun close() {
        if (disposed) return
        closePreview(); editor?.close(); editor = null
        disposed = true
        owner.removeHierarchyListener(visibility)
        changed.close(); target = null
        loader.close()
        preparationGeneration++
        preparation?.close()
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
    private val listeners = linkedSetOf<(Set<Pair<String, String>>) -> Unit>()

    fun listen(listener: (Set<Pair<String, String>>) -> Unit): AutoCloseable {
        listeners += listener
        listener(nodeKeys())
        return AutoCloseable { listeners -= listener }
    }

    fun notifyListeners() {
        val nodes = nodeKeys()
        listeners.toList().forEach { it(nodes) }
    }

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

    fun noteDrafts(): List<CanvasNoteDraftEntry> = values.entries.mapNotNull { (key, text) ->
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
