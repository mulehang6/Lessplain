package com.lessplain.tutorial

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.lessplain.ide.captureEditorAnchor
import com.lessplain.ide.captureSourceFocus
import java.nio.file.Path

class LearnSelectedCodeAction : AnAction("学习选中内容", "冻结源码选区并生成教程问题", AllIcons.General.Information), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val base = event.project?.basePath
        event.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection() && file?.fileSystem?.protocol == "file" && base != null &&
            Path.of(file.path).toAbsolutePath().normalize().startsWith(Path.of(base).toAbsolutePath().normalize())
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val anchor = captureEditorAnchor(project, editor) ?: return
        val focus = captureSourceFocus(editor.document.text, anchor.path, editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd, anchor.symbol)
        project.getService(TutorialService::class.java).startLearning(focus.anchor, focus.start, focus.end)
    }
}

class OpenTutorialsAction : AnAction("教程目录"), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        showTutorialLibrary(project, event.inputEvent?.component)
    }
}
