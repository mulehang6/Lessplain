package com.lessplain

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.lessplain.ide.UnderstandingService
import com.lessplain.ui.UnderstandingPanel
import com.lessplain.ui.OpenUnderstandingInEditorAction
import com.lessplain.ui.WelcomeAction
import com.lessplain.ui.RecentUnderstandingAction
import com.lessplain.ui.UnderstandingHistoryAction
import org.jetbrains.jewel.bridge.JewelComposePanel

class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(UnderstandingService::class.java)
        toolWindow.setTitleActions(listOf(WelcomeAction(service), RecentUnderstandingAction(service),
            UnderstandingHistoryAction(service), OpenUnderstandingInEditorAction(project, service)))
        val panel = JewelComposePanel { UnderstandingPanel(service) }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}
