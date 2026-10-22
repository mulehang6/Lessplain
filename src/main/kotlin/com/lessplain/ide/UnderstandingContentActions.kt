package com.lessplain.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.lessplain.model.UnderstandingQuestion
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.editPendingQuestion
import com.lessplain.storage.updateDisplayTitle
import com.lessplain.ui.SelectedCodePreview
import com.lessplain.ui.questionEditorTitle
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point

/** 工具窗口和编辑器画布共用与来源绑定的修改操作；会话管理临时界面状态。 */
internal class UnderstandingContentActions(private val service: UnderstandingService, private val project: Project) :
    AutoCloseable {
    internal val sourceQuestions = SourceSelectionQuestionSession(service)
    private val sessions = mutableMapOf<Component, CanvasContentSession>()

    private fun session(owner: Component): CanvasContentSession = sessions.getOrPut(owner) {
        CanvasContentSession(service, owner, { sessions.remove(owner) }, ::addNodeNote, ::editNodeNote)
    }

    fun addNodeNote(
        record: UnderstandingRecord, nodeId: String,
        component: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner, point: Point = Point()
    ) {
        if (service.readOnly || component == null) return
        val label = record.graph.nodes.firstOrNull { it.id == nodeId }?.label ?: return
        val fixedPath = (service.graphStates.get(GraphLayerKey(record.id)).browse
            ?: service.storage.browse(record.id))?.staticPath.orEmpty().toList()
        session(component).edit(noteKey(record.id, nodeId, ""), questionEditorTitle(label), "", point, true) { text ->
            val current = service.repository.getRecord(record.id)
            check(current.revision == record.revision) { "来源记录已更新，请重新打开注释；输入已保留" }
            val node = current.graph.nodes.firstOrNull { it.id == nodeId } ?: error("节点已不存在")
            val anchor = node.anchor ?: current.graph.evidence.firstOrNull { it.id in node.evidenceIds }?.anchor
            val context = service.core.captureContext(record.id, nodeId, anchor, fixedPath)
            service.core.prepareQuestion(text, context.token)
        }
    }

    private fun editNodeNote(
        record: UnderstandingRecord, nodeId: String, note: UnderstandingQuestion,
        component: Component, point: Point
    ) {
        if (service.readOnly) return
        val target = session(component)
        target.prepare({ nodeQuestionPresentation(service.repository, record.id, nodeId, note) }) { presentation ->
            target.edit(
                noteKey(record.id, nodeId, note.id), presentation.title, note.text, point, true,
                selectionPreview = presentation.anchor?.let { SelectedCodePreview(project, it) }) { text ->
                service.repository.editPendingQuestion(note.id, text)
            }
        }
    }

    fun showNodeNotes(record: UnderstandingRecord, nodeId: String, component: Component, point: Point) =
        session(component).show(record, nodeId, point)

    fun onDraftNotesChanged(component: Component, listener: (Set<Pair<String, String>>) -> Unit): AutoCloseable =
        session(component).onDraftNotesChanged(listener)

    fun editRecordTitle(
        recordId: String,
        component: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner, point: Point = Point()
    ) {
        if (service.readOnly || component == null) return
        val target = session(component)
        target.prepare({ service.storage.loadRecord(recordId) }) { record ->
            target.edit(
                "title:$recordId", "编辑标题", record.displayTitle ?: record.question, point, false,
                afterSave = service::refreshOpenDiagrams
            ) { value ->
                service.repository.updateDisplayTitle(recordId, value, record.revision)
                service.storage.loadRecord(recordId)
            }
        }
    }

    /** 只修改真实节点标签，复用图版本事务保留来源、身份与批注。 */
    fun renameNode(record: UnderstandingRecord, nodeId: String, component: Component, point: Point) {
        if (service.readOnly) return
        val node = record.graph.nodes.firstOrNull { it.id == nodeId } ?: return
        session(component).edit("node-label:${record.id}:$nodeId", "重命名节点", node.label, point, false,
            afterSave = service::refreshOpenDiagrams) { label ->
            service.core.revise(com.lessplain.model.UNDERSTANDING_SCHEMA_REVISION,
                com.lessplain.storage.sha256("rename:${record.id}:${record.revision}:$nodeId:$label"), record.question,
                record.graph.copy(nodes = record.graph.nodes.map { if (it.id == nodeId) it.copy(label = label) else it }), record.id, record.revision)
            service.storage.loadRecord(record.id)
        }
    }

    fun notifyQuestionCopy(message: String, error: Boolean) {
        if (!project.isDisposed) NotificationGroupManager.getInstance()
            .getNotificationGroup("code.comprehension.results")
            .createNotification(message, if (error) NotificationType.ERROR else NotificationType.INFORMATION)
            .notify(project)
    }

    override fun close() {
        sourceQuestions.close()
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
    }
}
