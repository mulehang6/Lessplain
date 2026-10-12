package com.lessplain.core

import kotlinx.coroutines.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** 项目和插件作用域拥有实际任务；取消会中断阻塞读取，已排队的结果也不能越过关闭边界。 */
internal class BackgroundTasks(
    parent: CoroutineScope,
    private val post: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    fun <T> dispatch(read: () -> T, completed: (Result<T>) -> Unit): AutoCloseable {
        val cancelled = AtomicBoolean()
        val job = scope.launch {
            val result = runInterruptible(Dispatchers.IO) { cancellationAwareResult(read) }
            post { if (scope.isActive && !cancelled.get()) completed(result) }
        }
        return AutoCloseable { cancelled.set(true); job.cancel() }
    }

    override fun close() {
        scope.cancel()
    }
}

/** 取消属于控制流，不能转成用户错误或警告。 */
internal fun <T> cancellationAwareResult(action: () -> T): Result<T> = try {
    Result.success(action())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
