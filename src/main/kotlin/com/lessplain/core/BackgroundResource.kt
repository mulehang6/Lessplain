package com.lessplain.core

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** 仅后台调用 get；关闭不等待初始化，若资源随后才创建成功，由创建线程立即释放。 */
internal class BackgroundResource<T : AutoCloseable>(private val create: () -> T) : AutoCloseable {
    private var loading: CompletableFuture<T>? = null
    private var value: T? = null
    private var closed = false

    fun get(): T {
        var initialize = false
        val future = synchronized(this) {
            if (closed) throw CancellationException("资源已关闭")
            loading ?: CompletableFuture<T>().also { loading = it; initialize = true }
        }
        if (initialize) {
            try {
                val created = create()
                val accepted = synchronized(this) {
                    if (closed) false else {
                        value = created; true
                    }
                }
                if (!accepted) {
                    created.close()
                    throw CancellationException("资源初始化期间已关闭")
                }
                future.complete(created)
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
                throw failure
            }
        }
        return try {
            future.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    override fun close() {
        val previous = synchronized(this) {
            if (closed) return
            closed = true
            value.also { value = null }
        }
        previous?.close()
    }
}
