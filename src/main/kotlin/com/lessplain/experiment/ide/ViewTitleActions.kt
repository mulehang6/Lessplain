package com.lessplain.experiment.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** 历史与编辑器入口属于原生工具窗口标题栏，不占用答案画布。 */
internal class ViewHistoryAction : AnAction("历史记录", "查看全部理解记录", AllIcons.Vcs.History), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.getService(ViewService::class.java)?.showHistory()
    }
}

internal class OpenViewEditorAction : AnAction("在编辑器打开", "在编辑器打开当前理解层级", AllIcons.Actions.OpenNewTab), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.getService(ViewService::class.java)?.toolWindowRef != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        val service = event.project?.getService(ViewService::class.java) ?: return
        service.toolWindowRef?.let(service::openEditor)
    }
}
