package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.captureEditorAnchor
import com.lessplain.ide.selectedSourceBinding

/** 浮动工具栏与编辑器右键共用入口，只接收动作事件中的具体编辑器。 */
class AskSelectedCodeAction : AnAction("提问", "将所选代码的问题添加到当前源码节点", AllIcons.General.QuestionDialog), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null && editor != null &&
            editor.project == project && selectedSourceBinding(editor) != null &&
            !project.getService(UnderstandingService::class.java).readOnly
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val binding = selectedSourceBinding(editor) ?: return
        val anchor = captureEditorAnchor(project, editor) ?: return
        com.lessplain.core.cancellationAwareResult {
            project.getService(UnderstandingService::class.java).contentActions.sourceQuestions.show(editor, binding, anchor)
        }.onFailure {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, it.message ?: "无法打开提问，请重新选择", "提问")
        }
    }
}
