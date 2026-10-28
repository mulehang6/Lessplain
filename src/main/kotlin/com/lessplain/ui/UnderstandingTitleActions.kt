package com.lessplain.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.lessplain.ide.UnderstandingService

internal class UnderstandingHistoryAction(private val service: UnderstandingService) :
    AnAction("历史", null, TitleActionIcon(TitleActionKind.HISTORY)) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val unread = service.resultInbox.pending().isNotEmpty()
        event.presentation.icon = TitleActionIcon(TitleActionKind.HISTORY, unread)
        event.presentation.description = if (unread) "历史，有未读" else "历史"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val anchor = event.inputEvent?.component
        showNativeMenu(anchor, historyMenu(service.resultInbox.pending().isNotEmpty(),
            { if (anchor != null) showRecentRecordPopup(service, anchor) }, service::showHistory), project = event.project)
    }
}

internal fun historyMenu(unread: Boolean, recent: () -> Unit, history: () -> Unit) = listOf(
    NativeMenuItem("最近", perform = recent),
    NativeMenuItem("历史", icon = UnreadDotIcon.takeIf { unread }, perform = history),
)
