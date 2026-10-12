package com.lessplain.diagram

import com.intellij.openapi.ide.CopyPasteManager
import com.lessplain.core.PendingQuestionHandoffSession
import com.lessplain.core.preparePendingQuestionCopy
import com.lessplain.core.replaceQuestionClipboard
import com.lessplain.ide.UnderstandingService
import com.lessplain.storage.pendingNoteScope

/** 每个画布绑定自己的持久化记录，编辑器、根图与追问画布共用同一入口。 */
internal fun UnderstandingDiagram.bindPendingQuestionHandoff(
    service: UnderstandingService,
    recordId: String,
    notify: (String, Boolean) -> Unit,
): AutoCloseable {
    lateinit var session: PendingQuestionHandoffSession
    session = PendingQuestionHandoffSession(
        loadScope = { service.repository.pendingNoteScope(recordId) },
        loadCopy = { service.repository.preparePendingQuestionCopy(it) },
        scopeDispatch = service::readBackground,
        subscribe = service::onChanged,
        dispatch = service::readBackground,
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
