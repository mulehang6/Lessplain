package com.master.understanding.diagram

import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/** Runs one layout at a time and retains only the newest request. */
internal class DiagramLayoutCoordinator(
    private val compute: (LayoutDirection) -> UnderstandingGraphLayout,
    private val onResult: (LayoutDirection, Result<UnderstandingGraphLayout>) -> Unit,
) : AutoCloseable {
    private data class Request(val version: Long, val direction: LayoutDirection)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "understanding-elk-layout").apply { isDaemon = true }
    }
    private var version = 0L
    private var running = false
    private var pending: Request? = null
    private var closed = false
    private val geometryCache = mutableMapOf<LayoutDirection, UnderstandingGraphLayout>()

    fun request(direction: LayoutDirection) {
        if (closed) return
        pending = Request(++version, direction)
        if (!running) startNext()
    }

    private fun startNext() {
        val request = pending ?: return
        pending = null
        running = true
        executor.execute {
            val result = runCatching {
                geometryCache.getOrPut(request.direction) { compute(request.direction) }
            }
            SwingUtilities.invokeLater {
                running = false
                if (!closed && request.version == version) onResult(request.direction, result)
                if (!closed) startNext()
            }
        }
    }

    override fun close() {
        closed = true
        version++
        pending = null
        executor.shutdownNow()
    }
}
