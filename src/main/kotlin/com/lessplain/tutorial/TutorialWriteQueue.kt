package com.lessplain.tutorial

import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 顺序写入并按文件保留最新失败操作，成功重写不能被旧失败覆盖。 */
internal class TutorialWriteQueue {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Lessplain tutorial persistence").apply { isDaemon = true } }
    private val failed = linkedMapOf<Path, Pair<() -> Unit, Throwable>>()
    @Volatile var failure: Throwable? = null
        private set
    fun <T> submit(action: () -> T): Future<T> = executor.submit(Callable(action))
    fun commit(path: Path, action: () -> Unit): Future<Unit> = submit {
        action()
        failed.remove(path)
        failure = failed.values.firstOrNull()?.second
    }
    fun write(path: Path, action: () -> Unit) { submit { attempt(path, action) } }
    private fun attempt(path: Path, action: () -> Unit) {
        try { action(); failed.remove(path) }
        catch (problem: Exception) { failed[path] = action to problem }
        failure = failed.values.firstOrNull()?.second
    }
    private fun retry() { failed.toMap().forEach { (path, value) -> attempt(path, value.first) } }
    fun retryPending() { submit { retry() } }
    fun discard(directory: Path) { submit { failed.keys.removeAll { it.startsWith(directory) }; failure = failed.values.firstOrNull()?.second } }
    fun checkpoint(): Future<Throwable?> = submit { retry(); failure }
    fun shutdown() = executor.shutdown()
    fun awaitTermination(timeout: Long, unit: TimeUnit) = executor.awaitTermination(timeout, unit)
}

/** 一篇教程及其派生来源、批注和重试回执作为同一份原子快照提交。 */
internal data class TutorialContentState(
    val record: TutorialRecord,
    val annotations: List<TutorialAnnotation>,
    val receipts: Map<String, TutorialRecord>,
    val resources: TutorialResourceState? = null,
    val toolReceipts: Map<String, String>? = null,
    val removedContexts: Set<String>? = null,
)
