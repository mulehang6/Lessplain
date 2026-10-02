package com.master.understanding.ide

import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.storage.editPendingQuestion
import com.master.understanding.storage.updateDisplayTitle
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point

/** Source-bound mutations shared by tool-window and editor canvases; sessions own transient UI. */
internal class UnderstandingContentActions(private val service: UnderstandingService) : AutoCloseable {
    private val sessions = mutableMapOf<Component, CanvasContentSession>()

    private fun session(owner: Component): CanvasContentSession = sessions.getOrPut(owner) {
        CanvasContentSession(service, owner, { sessions.remove(owner) }, ::addNodeNote, ::editNodeNote)
    }

    fun addNodeNote(record: UnderstandingRecord, nodeId: String,
        component: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner, point: Point = Point()) {
        if (service.repository.readOnly || component == null) return
        session(component).edit(noteKey(record.id, nodeId, ""), "添加注释", "", point, true) { text ->
            val current = service.repository.getRecord(record.id)
            val node = current.graph.nodes.firstOrNull { it.id == nodeId } ?: error("节点已不存在")
            val anchor = node.anchor ?: current.graph.evidence.firstOrNull { it.id in node.evidenceIds }?.anchor
            val context = service.core.captureContext(record.id, nodeId, anchor,
                service.repository.getBrowseState(record.id)?.staticPath.orEmpty())
            service.core.prepareQuestion(text, context.token)
        }
    }

    private fun editNodeNote(record: UnderstandingRecord, nodeId: String, note: UnderstandingQuestion,
        component: Component, point: Point) {
        if (service.repository.readOnly) return
        session(component).edit(noteKey(record.id, nodeId, note.id), "编辑注释", note.text, point, true) { text ->
            service.repository.editPendingQuestion(note.id, text)
        }
    }

    fun showNodeNotes(record: UnderstandingRecord, nodeId: String, component: Component, point: Point) =
        session(component).show(record, nodeId, point)

    fun onDraftNotesChanged(component: Component, listener: (Set<Pair<String, String>>) -> Unit): AutoCloseable =
        session(component).onDraftNotesChanged(listener)

    fun editRecordTitle(recordId: String,
        component: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner, point: Point = Point()) {
        if (service.repository.readOnly || component == null) return
        val record = service.repository.getRecord(recordId)
        session(component).edit("title:$recordId", "编辑标题", record.displayTitle ?: record.question, point, false) { value ->
            service.repository.updateDisplayTitle(recordId, value, record.revision)
            service.refreshOpenDiagrams()
        }
    }

    override fun close() {
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
    }
}
