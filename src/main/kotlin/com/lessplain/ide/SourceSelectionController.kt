package com.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.lessplain.core.cancellationAwareResult
import com.lessplain.model.SourceAnchor
import org.jetbrains.concurrency.CancellablePromise

/** 选择切换取消旧 PSI 工作，文档提交和可取消读操作由平台调度，结果只交给仍有效的选择。 */
internal class SourceSelectionController(
    private val project: Project,
    private val parent: Disposable,
) : AutoCloseable {
    private var task: CancellablePromise<Result<SourceResolution>>? = null
    @Volatile
    private var generation = 0L
    @Volatile
    private var closed = false

    fun request(anchor: SourceAnchor, valid: () -> Boolean, completed: (Result<SourceResolution>) -> Unit) {
        cancel()
        if (closed) return
        val request = generation
        task = ReadAction.nonBlocking<Result<SourceResolution>> {
            cancellationAwareResult { resolveSourceInReadAction(project, anchor) }
        }.withDocumentsCommitted(project).expireWith(parent)
            .expireWhen { closed || request != generation }
            .finishOnUiThread(ModalityState.nonModal()) { result ->
                if (!closed && request == generation && valid()) {
                    if (result.getOrNull()?.isCurrent() == false) request(anchor, valid, completed)
                    else completed(result)
                }
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    fun cancel() {
        generation++
        task?.cancel()
        task = null
    }

    override fun close() {
        closed = true; cancel()
    }
}
