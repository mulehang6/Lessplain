package com.lessplain.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.lessplain.ui.DeleteRecordState

/** 每个项目同时仅管理一个平台对话框，目标固定且需明确确认。 */
internal class MaterialDeletionConfirmation(
    private val project: Project,
    delete: (String) -> Unit,
) : AutoCloseable {
    private val session = DeleteRecordSession(delete) { state, confirm, cancel, closed ->
        checkNotNull(WindowManager.getInstance().getFrame(project)) { "项目窗口已关闭" }
        DeleteRecordDialog(project, state, confirm, cancel, closed)
    }

    val isOpen: Boolean get() = session.isOpen

    fun show(id: String, title: String, kind: String = "理解图", description: String = "永久删除这张理解图及其全部追问和回答？此操作无法撤销。") {
        if (!project.isDisposed && WindowManager.getInstance().getFrame(project) != null)
            session.show(id, title, kind, description)
    }

    override fun close() { session.close() }
}

/** 显示模态窗口前先持有句柄，其嵌套事件循环可能释放所属对象。 */
internal interface DeleteRecordWindow : AutoCloseable {
    fun show()
}

/** 将删除动作的生命周期与窗口实现分开，并处理过期关闭事件。 */
internal class DeleteRecordSession(
    private val delete: (String) -> Unit,
    private val open: (DeleteRecordState, () -> Unit, () -> Unit, () -> Unit) -> DeleteRecordWindow,
) : AutoCloseable {
    private var popup: DeleteRecordWindow? = null
    private var state: DeleteRecordState? = null
    private var disposed = false
    val isOpen: Boolean get() = state != null

    fun show(recordId: String, title: String, kind: String = "理解图", description: String = "永久删除这张理解图及其全部追问和回答？此操作无法撤销。") {
        if (disposed) return
        dismiss()
        val current = DeleteRecordState(recordId, title, kind, description)
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
