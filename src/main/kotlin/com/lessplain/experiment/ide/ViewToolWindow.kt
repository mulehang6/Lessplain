package com.lessplain.experiment.ide

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.lessplain.experiment.browser.ViewBrowser
import com.lessplain.ide.captureEditorAnchor

internal const val VIEW_TOOL_WINDOW_ID = "Lessplain"

class ViewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: com.intellij.openapi.project.Project, toolWindow: ToolWindow) {
        val host = ViewBrowser(project)
        val content = ContentFactory.getInstance().createContent(host.component, "", false)
        content.setDisposer(host)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(ViewHistoryAction(), OpenViewEditorAction()))
    }
}

/** 动作使用调用它的编辑器，保留所选源码而不读取后来切换的全局编辑器。 */
class AskSourceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        if (!editor.selectionModel.hasSelection() || editor.selectionModel.selectedText.isNullOrBlank()) return
        val service = project.getService(ViewService::class.java)
        service.samples.location(editor)?.let { service.openSampleQuestion(editor, it); return }
        val anchor = captureEditorAnchor(project, editor) ?: return
        service.openSourceQuestion(editor, anchor, service.sources.bindingFor(editor))
    }
}
