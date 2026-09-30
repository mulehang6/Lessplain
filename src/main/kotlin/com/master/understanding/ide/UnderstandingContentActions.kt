package com.master.understanding.ide

import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.storage.editPendingQuestion
import com.master.understanding.storage.pendingQuestionsForNode
import com.master.understanding.storage.updateDisplayTitle
import java.awt.Component
import java.awt.Point
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextArea

/** Dialog and node-note actions shared by the tool window and editor canvas. */
internal class UnderstandingContentActions(private val service: UnderstandingService) {
    fun addNodeNote(record: UnderstandingRecord, nodeId: String) {
        val repository = service.repository
        if (repository.readOnly) return
        val text = promptText("添加注释", "") ?: return
        if (text.isBlank()) return
        val node = record.graph.nodes.firstOrNull { it.id == nodeId } ?: return
        val anchor = node.anchor ?: record.graph.evidence.firstOrNull { it.id in node.evidenceIds }?.anchor
        val context = service.core.captureContext(record.id, nodeId, anchor,
            repository.getBrowseState(record.id)?.staticPath.orEmpty())
        service.selectedPendingQuestionId = service.core.prepareQuestion(text, context.token).question.id
        service.notifyChanged()
    }

    fun showNodeNotes(record: UnderstandingRecord, nodeId: String, component: Component, point: Point) {
        val repository = service.repository
        val notes = repository.pendingQuestionsForNode(record.id, nodeId)
        if (notes.isEmpty()) return
        JPopupMenu().apply {
            notes.forEach { note ->
                val selected = note.id == service.selectedPendingQuestionId
                val title = note.text.replace('\n', ' ').take(42)
                add(JMenuItem((if (selected) "✓ " else "") + title).apply {
                    addActionListener {
                        service.selectedPendingQuestionId = note.id
                        service.notifyChanged()
                        if (!repository.readOnly) {
                            val updated = promptText("节点注释", note.text)
                            if (updated != null && updated.isNotBlank() && updated.trim() != note.text) {
                                repository.editPendingQuestion(note.id, updated)
                                service.notifyChanged()
                            }
                        } else JOptionPane.showMessageDialog(component, note.text, "节点注释", JOptionPane.INFORMATION_MESSAGE)
                    }
                })
            }
            show(component, point.x, point.y)
        }
    }

    fun editRecordTitle(recordId: String) {
        val repository = service.repository
        if (repository.readOnly) return
        val record = repository.getRecord(recordId)
        var initial = record.displayTitle ?: record.question
        while (true) {
            val title = promptText("编辑标题", initial) ?: return
            if (title.isBlank()) {
                JOptionPane.showMessageDialog(null, "标题不能为空", "编辑标题", JOptionPane.WARNING_MESSAGE)
                initial = title
                continue
            }
            repository.updateDisplayTitle(recordId, title)
            service.refreshOpenDiagrams()
            service.notifyChanged()
            return
        }
    }

    private fun promptText(title: String, initial: String): String? {
        val input = JTextArea(initial, 5, 36).apply { lineWrap = true; wrapStyleWord = true }
        return if (JOptionPane.showConfirmDialog(null, JScrollPane(input), title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) input.text else null
    }
}
