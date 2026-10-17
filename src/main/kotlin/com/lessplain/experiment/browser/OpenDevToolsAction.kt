package com.lessplain.experiment.browser

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager

internal val LESSPLAIN_DEVTOOLS: DataKey<Runnable> = DataKey.create("Lessplain.OpenDevTools")
internal val LESSPLAIN_DIAGNOSTICS: DataKey<Runnable> = DataKey.create("Lessplain.CaptureDiagnostics")

/** Find Action 使用来源组件的冻结回调，不误打开另一个编辑器或工具窗口的浏览器。 */
class OpenDevToolsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = ApplicationManager.getApplication().isInternal && event.getData(LESSPLAIN_DEVTOOLS) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (ApplicationManager.getApplication().isInternal) event.getData(LESSPLAIN_DEVTOOLS)?.run()
    }
}

/** 同一宿主的后台状态采样入口，不依赖能否画出 DevTools 窗口。 */
class CaptureBrowserDiagnosticsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = ApplicationManager.getApplication().isInternal && event.getData(LESSPLAIN_DIAGNOSTICS) != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        if (ApplicationManager.getApplication().isInternal) event.getData(LESSPLAIN_DIAGNOSTICS)?.run()
    }
}
