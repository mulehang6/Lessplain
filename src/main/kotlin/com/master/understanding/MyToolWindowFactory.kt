package com.master.understanding

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.ui.UnderstandingPanel
import com.master.understanding.ui.OpenUnderstandingInEditorAction
import com.master.understanding.ui.WelcomeAction
import com.master.understanding.ui.RecentUnderstandingAction
import com.master.understanding.ui.UnderstandingHistoryAction
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
