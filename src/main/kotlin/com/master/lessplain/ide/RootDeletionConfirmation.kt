package com.master.lessplain.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.master.lessplain.model.UnderstandingRecord
import com.master.lessplain.ui.DeleteRecordState

/** One project-owned platform dialog at a time, with explicit acceptance and fixed target. */
internal class RootDeletionConfirmation(
    private val project: Project,
    delete: (String) -> Unit,
) : AutoCloseable {
    private val session = DeleteRecordSession(delete) { state, confirm, cancel, closed ->
        checkNotNull(WindowManager.getInstance().getFrame(project)) { "项目窗口已关闭" }
        DeleteRecordDialog(project, state, confirm, cancel, closed)
    }

    fun show(record: UnderstandingRecord) {
        if (!project.isDisposed && WindowManager.getInstance().getFrame(project) != null)
            session.show(record.id, record.displayTitle ?: record.question)
    }

    override fun close() { session.close() }
}

/** The handle is owned before showing a modal window, whose nested event loop can dispose its owner. */
internal interface DeleteRecordWindow : AutoCloseable {
    fun show()
}

/** Separates destructive-action ownership from the window backend, including stale close events. */
internal class DeleteRecordSession(
    private val delete: (String) -> Unit,
    private val open: (DeleteRecordState, () -> Unit, () -> Unit, () -> Unit) -> DeleteRecordWindow,
) : AutoCloseable {
    private var popup: DeleteRecordWindow? = null
    private var state: DeleteRecordState? = null
    private var disposed = false

    fun show(recordId: String, title: String) {
        if (disposed) return
        dismiss()
        val current = DeleteRecordState(recordId, title)
        state = current
        try {
            val handle = open(current,
                { if (!disposed && state === current && current.confirm(delete)) dismiss() },
                { if (state === current) dismiss() },
                { if (state === current) { current.cancel(); state = null; popup = null } })
            if (state === current) {
                popup = handle
                handle.show()
            } else handle.close()
        } catch (failure: RuntimeException) { dismiss(); throw failure }
    }

    private fun dismiss() {
        state?.cancel()
        state = null
        val previous = popup
        popup = null
        previous?.close()
    }

    override fun close() { disposed = true; dismiss() }
}
