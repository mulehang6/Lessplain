package com.lessplain.core

/** 界面立即更新快照，后台按键合并写入；同一键的旧写入不能晚于新写入完成。 */
internal class CoalescingBackgroundWrite<K, V>(
    private val dispatch: BackgroundReadDispatch<Unit>,
    private val write: (V) -> Unit,
    private val failed: (Throwable) -> Unit,
) : AutoCloseable {
    private val pending = linkedMapOf<K, V>()
    private var running = false
    private var closed = false
    private var task: AutoCloseable? = null
    private var generation = 0L

    fun submit(key: K, value: V) {
        if (closed) return
        pending[key] = value
        if (!running) start()
    }

    private fun start() {
        if (closed || pending.isEmpty()) return
        val batch = pending.values.toList()
        pending.clear()
        running = true
        val request = ++generation
        val started = dispatch({ batch.forEach(write) }) { result ->
            task = null
            running = false
            if (!closed) {
                result.exceptionOrNull()?.let(failed)
                start()
            }
        }
        if (closed || !running || request != generation) started.close() else task = started
    }

    override fun close() {
        closed = true
        pending.clear()
        task?.close()
        task = null
    }
}
