package com.lessplain.tutorial

import javax.swing.Timer

internal enum class AnnotationFloatOrigin { SELECTION, HOVER }

/** 文字与浮条各自接收进入/离开事件，短暂跨过间隙不等于离开整个入口。 */
internal class TutorialFloatLifetime(private val hide: () -> Unit) : AutoCloseable {
    var origin: AnnotationFloatOrigin? = null
        private set
    private var inText = false
    private var inBar = false
    private val exitTimer = Timer(200) { timeout() }.apply { isRepeats = false }

    fun shown(value: AnnotationFloatOrigin) {
        exitTimer.stop()
        origin = value
        inText = true
        inBar = false
    }
    fun text(inside: Boolean) { if (inText != inside) { inText = inside; schedule() } }
    fun bar(inside: Boolean) { if (inBar != inside) { inBar = inside; schedule() } }
    private fun schedule() {
        if (origin == AnnotationFloatOrigin.HOVER && !inText && !inBar) exitTimer.restart() else exitTimer.stop()
    }
    fun timeout() {
        if (origin == AnnotationFloatOrigin.HOVER && !inText && !inBar) { clear(); hide() }
    }
    fun clear() { exitTimer.stop(); origin = null; inText = false; inBar = false }
    override fun close() = clear()
}
