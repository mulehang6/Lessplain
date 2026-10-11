package com.lessplain.diagram

import com.intellij.openapi.ide.CopyPasteManager
import com.lessplain.core.PendingQuestionHandoffSession
import com.lessplain.core.preparePendingQuestionCopy
import com.lessplain.core.replaceQuestionClipboard
import com.lessplain.ide.UnderstandingService
import com.lessplain.storage.pendingNoteScope
import com.lessplain.ui.dispatchBackgroundRead
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/** 每个画布绑定自己的持久化记录，编辑器、根图与追问画布共用同一入口。 */
internal fun UnderstandingDiagram.bindPendingQuestionHandoff(
    service: UnderstandingService,
    recordId: String,
    notify: (String, Boolean) -> Unit,
): AutoCloseable {
    lateinit var session: PendingQuestionHandoffSession
    session = PendingQuestionHandoffSession(
        loadScope = { service.repository.pendingNoteScope(recordId) },
        loadCopy = service.repository::preparePendingQuestionCopy,
        scopeDispatch = ::dispatchBackgroundRead,
        subscribe = service::onChanged,
        dispatch = { read, completed ->
            // 完整批次包含图和上下文，只在点击后后台读取。
            CompletableFuture.supplyAsync(read).whenComplete { value, failure ->
                SwingUtilities.invokeLater {
                    completed(if (failure == null) Result.success(value) else Result.failure(failure.cause ?: failure))
                }
            }
        },
        write = { copy ->
            val clipboard = CopyPasteManager.getInstance()
            replaceQuestionClipboard(copy, { clipboard.contents }, clipboard::setContents)
        },
        updated = { count, enabled, error ->
            controls.pendingQuestions.update(count, if (enabled) ({ session.copy() }) else null, error)
            controls.arrangeSide(width, height)
            repaint()
        },
        notify = notify,
    )
    return session
}
