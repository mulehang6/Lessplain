package com.master.understanding.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.master.understanding.diagram.openRecordInEditor
import com.master.understanding.ide.UnderstandingService

/** Opens the selected record from the tool window title bar. */
internal class OpenUnderstandingInEditorAction(
    private val project: Project,
    private val service: UnderstandingService,
) : AnAction("在编辑器打开图", null, AllIcons.Actions.OpenNewTab) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = service.activeRecordId != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val layer = service.panelLayers.lastOrNull()
        val id = layer?.recordId ?: service.activeRecordId ?: return
        val record = runCatching { service.repository.getRecord(id) }.getOrNull() ?: return
        openRecordInEditor(project, record, layer?.nodeId)
    }
}
