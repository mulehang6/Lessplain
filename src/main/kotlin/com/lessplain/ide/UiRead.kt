package com.lessplain.ide

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.util.concurrency.ThreadingAssertions

/** 在 EDT 回调中显式建立写意图读域，兼容未持锁的 Swing 事件并允许内部写命令升级。 */
internal fun <T> uiRead(action: () -> T): T {
    ThreadingAssertions.assertEventDispatchThread()
    return WriteIntentReadAction.compute<T> { action() }
}
