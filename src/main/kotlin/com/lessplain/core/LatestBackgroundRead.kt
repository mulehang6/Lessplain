package com.lessplain.core

/** 调度器在后台执行读取，并在界面线程交付结果。 */
internal typealias BackgroundReadDispatch<T> = (() -> T, (Result<T>) -> Unit) -> Unit

/** 所有状态在界面线程使用；同时只读一次，合并连续刷新并丢弃过期结果。 */
internal class LatestBackgroundRead<T>(
    private val dispatch: BackgroundReadDispatch<T>,
    private val publish: (Result<T>) -> Unit,
) : AutoCloseable {
    private data class Request<T>(val version: Long, val read: () -> T)
    private var version = 0L
    private var running = false
    private var pending: Request<T>? = null
    private var closed = false

    fun request(read: () -> T) {
        if (closed) return
        pending = Request(++version, read)
        if (!running) startNext()
    }

    private fun startNext() {
        val request = pending ?: return
        pending = null
        running = true
        try {
            dispatch(request.read) { result -> complete(request, result) }
        } catch (failure: Exception) {
            complete(request, Result.failure(failure))
        }
    }

    private fun complete(request: Request<T>, result: Result<T>) {
        running = false
        if (closed) return
        if (request.version == version) publish(result)
        if (!closed) startNext()
    }

    override fun close() {
        closed = true
        version++
        pending = null
    }
}
