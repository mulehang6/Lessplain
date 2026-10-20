package com.lessplain.lesson

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction

/** Swing 回调本身不保证拥有平台读权限；编辑器与 PSI 相关 UI 显式进入短读域。 */
internal fun <T> lessonUiRead(action: () -> T): T {
    ApplicationManager.getApplication().assertIsDispatchThread()
    return WriteIntentReadAction.compute<T> { action() }
}

internal data class LessonDestination(val blockId: String? = null, val owner: String? = null, val annotationId: String? = null)
