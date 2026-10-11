package com.lessplain.core

import com.lessplain.storage.PendingNoteScope

/** 管理单个画布的数量和复制状态；后台读取结束时仍使用点击时固定的根范围。 */
internal class PendingQuestionHandoffSession(
    private val loadScope: () -> PendingNoteScope,
    private val loadCopy: (String) -> PendingQuestionCopy,
    scopeDispatch: BackgroundReadDispatch<PendingNoteScope>,
    subscribe: (() -> Unit) -> AutoCloseable,
    private val dispatch: (() -> PendingQuestionCopy, (Result<PendingQuestionCopy>) -> Unit) -> Unit,
    private val write: (PendingQuestionCopy) -> Unit,
    private val updated: (Int, Boolean, String?) -> Unit,
    private val notify: (String, Boolean) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var busy = false
    private var scope: PendingNoteScope? = null
    private val scopeLoader = LatestBackgroundRead(scopeDispatch) { result ->
        result.onSuccess {
            scope = it
            updated(it.count, !busy && it.count > 0, null)
        }.onFailure {
            scope = null
            updated(0, false, it.message ?: "无法读取待答注释")
        }
    }
    private val subscription = subscribe(::refresh)

    init { updated(0, false, null); refresh() }

    private fun refresh() {
        if (!closed) scopeLoader.request(loadScope)
    }

    fun copy() {
        if (closed || busy) return
        val fixedScope = scope?.takeIf { it.count > 0 }?.recordId ?: return
        busy = true
        updated(scope?.count ?: 0, false, null)
        try {
            dispatch({ loadCopy(fixedScope) }) { result ->
                if (closed) return@dispatch
                try {
                    val copy = result.getOrThrow()
                    if (copy.count == 0) {
                        notify("没有待答注释", false)
                    } else {
                        write(copy)
                        notify("已复制 ${copy.count} 条待答注释", false)
                    }
                } catch (error: Exception) {
                    notify("复制提问失败：${error.message ?: "无法读取待答注释"}", true)
                } finally {
                    busy = false
                    refresh()
                }
            }
        } catch (error: Exception) {
            busy = false
            notify("复制提问失败：${error.message ?: "无法读取待答注释"}", true)
            refresh()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription.close()
        scopeLoader.close()
        scope = null
        updated(0, false, null)
    }
}
