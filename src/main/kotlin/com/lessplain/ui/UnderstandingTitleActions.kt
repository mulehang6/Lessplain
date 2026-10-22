package com.lessplain.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.lessplain.ide.UnderstandingService

/** 导航放在工具窗口框架中，使图表可使用完整内容高度。 */
internal class WelcomeAction(private val service: UnderstandingService) :
    AnAction("首页", null, TitleActionIcon(TitleActionKind.HOME)) {
    override fun actionPerformed(event: AnActionEvent) = service.showWelcome()
}

internal class UnderstandingHistoryAction(private val service: UnderstandingService) :
    AnAction("历史", null, TitleActionIcon(TitleActionKind.HISTORY)) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        val anchor = event.inputEvent?.component
        showNativeMenu(anchor, listOf(
            NativeMenuItem("最近记录") { if (anchor != null) showRecentRecordPopup(service, anchor) },
            NativeMenuItem("完整历史") { service.showHistory() },
        ), project = event.project)
    }
}
