package com.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction

/** 原生 UI 回调访问编辑器模型时进入短读域。 */
internal fun <T> uiRead(action: () -> T): T {
    ApplicationManager.getApplication().assertIsDispatchThread()
    return WriteIntentReadAction.compute<T> { action() }
}
