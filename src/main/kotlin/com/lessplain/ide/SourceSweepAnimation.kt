package com.lessplain.ide

import javax.swing.Timer

/** 源码和教学步骤共用 200ms 线性铺开；重新开始会替换旧进度，不排队。 */
internal class SourceSweepAnimation(
    private val clock: () -> Long = System::nanoTime,
    private val reducedMotion: () -> Boolean = { java.lang.Boolean.getBoolean("lessplain.reduceMotion") },
    private val repaint: () -> Unit,
) : AutoCloseable {
    var progress = 0f
        private set
    private var started = 0L
    private var closed = false
    private val timer = Timer(16) { advance() }
    val isRunning: Boolean get() = timer.isRunning

    fun start() {
        if (closed) return
        timer.stop()
        started = clock()
        progress = if (reducedMotion()) 1f else 0f
        if (progress < 1f) timer.start()
        repaint()
    }

    internal fun advance() {
        if (closed || !timer.isRunning) return
        progress = ((clock() - started) / 200_000_000f).coerceIn(0f, 1f)
        if (progress >= 1f) timer.stop()
        repaint()
    }

    fun reset() { if (closed) return; timer.stop(); progress = 0f; repaint() }
    fun finish() { if (closed) return; timer.stop(); progress = 1f; repaint() }
    override fun close() { closed = true; timer.stop() }
}
