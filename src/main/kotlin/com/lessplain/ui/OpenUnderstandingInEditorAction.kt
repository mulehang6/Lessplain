package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.lessplain.diagram.openRecordInEditor
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.PanelPage

/** 从工具窗口标题栏打开选中记录。 */
internal class OpenUnderstandingInEditorAction(
    private val project: Project,
    private val service: UnderstandingService,
) : AnAction("在编辑器打开图", null, AllIcons.Actions.OpenNewTab) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = service.panelPage == PanelPage.CANVAS && service.activeRecordId != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val layer = service.panelLayers.lastOrNull()
        val id = layer?.recordId ?: service.activeRecordId ?: return
        val record = runCatching { service.repository.getRecord(id) }.getOrNull() ?: return
        openRecordInEditor(project, record, layer?.nodeId)
    }
}
