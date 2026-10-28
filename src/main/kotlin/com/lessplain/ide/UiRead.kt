package com.lessplain.ide

import com.intellij.util.concurrency.ThreadingAssertions

/** 原生 UI 回调必须已有 EDT 与写意图读域，保留内部写命令升级的能力。 */
internal fun <T> uiRead(action: () -> T): T {
    ThreadingAssertions.assertEventDispatchThread()
    ThreadingAssertions.assertWriteIntentReadAccess()
    return action()
}
