package com.lessplain.tutorial

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.lessplain.ui.TitleActionIcon
import com.lessplain.ui.TitleActionKind

class OpenTutorialsAction : AnAction("教程目录", "查找和打开教程", TitleActionIcon(TitleActionKind.BOOK)), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        showTutorialLibrary(project, event.inputEvent?.component)
    }
}
