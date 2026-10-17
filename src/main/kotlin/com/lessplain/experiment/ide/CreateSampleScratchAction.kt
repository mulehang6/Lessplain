package com.lessplain.experiment.ide

import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/** 只有学习者主动调用时才创建可编辑副本，原样例及页面证据保持只读。 */
class CreateSampleScratchAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null &&
            (event.getData(CommonDataKeys.VIRTUAL_FILE) as? SampleVirtualFile)?.current == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val sample = event.getData(CommonDataKeys.VIRTUAL_FILE) as? SampleVirtualFile ?: return
        if (!sample.current) return
        val file = ScratchRootType.getInstance().createScratchFile(project, sample.name,
            (sample.fileType as? LanguageFileType)?.language, sample.location.sample.code)
        if (file != null) FileEditorManager.getInstance(project).openFile(file, true)
        else Messages.showErrorDialog(project, "无法创建练习副本；教学代码仍可在原标签中查看。", "Lessplain")
    }
}
