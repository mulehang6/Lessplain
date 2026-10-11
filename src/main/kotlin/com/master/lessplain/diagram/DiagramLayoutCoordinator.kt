package com.master.lessplain.diagram

import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** 同一时间只执行一次布局，仅保留最新请求。 */
internal class DiagramLayoutCoordinator(
    private val compute: (LayoutDirection) -> UnderstandingGraphLayout,
    private val onResult: (LayoutDirection, Result<UnderstandingGraphLayout>) -> Unit,
) : AutoCloseable {
    private data class Request(val version: Long, val direction: LayoutDirection, val generation: Long)
    private data class CacheKey(val direction: LayoutDirection, val generation: Long)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "understanding-elk-layout").apply { isDaemon = true }
    }
    private var version = 0L
    private var running = false
    private var pending: Request? = null
    private var closed = false
    private var generation = 0L
    private val geometryCache = ConcurrentHashMap<CacheKey, UnderstandingGraphLayout>()
    val busy: Boolean get() = running || pending != null

    fun request(direction: LayoutDirection) {
        if (closed) return
        pending = Request(++version, direction, generation)
        if (!running) startNext()
    }

    /** 字体或测量规则变化后，旧任务写回的缓存也不能被新一代请求复用。 */
    fun invalidateMeasurements() {
        generation++
        geometryCache.clear()
    }

    private fun startNext() {
        val request = pending ?: return
        pending = null
        running = true
        executor.execute {
            val result = runCatching {
                geometryCache.getOrPut(CacheKey(request.direction, request.generation)) { compute(request.direction) }
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
