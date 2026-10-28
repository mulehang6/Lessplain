package com.lessplain.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 已确认的持久化操作不随读取协程取消；所属服务关闭前排空队列，关闭后不再回调 UI。 */
internal class ConfirmedWrites(private val deliver: (() -> Unit) -> Unit) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Lessplain confirmed writes").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Synchronized fun <T> dispatch(action: () -> T, completed: (Result<T>) -> Unit) {
        check(!closed) { "持久化队列已关闭" }
        executor.execute {
            val result = try { Result.success(action()) } catch (failure: Exception) { Result.failure(failure) }
            deliver { if (!closed) completed(result) }
        }
    }
    override fun close() {
        synchronized(this) { if (!closed) { closed = true; executor.shutdown() } }
        var interrupted = false
        while (!executor.isTerminated) {
            try { executor.awaitTermination(1, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
