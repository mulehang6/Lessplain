package com.master.understanding.ui

/** The target is immutable even if history selection changes during confirmation. */
internal class DeleteRecordState(val recordId: String, val title: String) {
    var busy = false
        private set
    var error: String? = null
        private set
    private var finished = false

    fun confirm(delete: (String) -> Unit): Boolean {
        if (finished || busy) return false
        busy = true
        error = null
        return try {
            delete(recordId)
            finished = true
            true
        } catch (failure: Exception) {
            error = failure.message ?: "删除失败，请重试"
            false
        } finally { busy = false }
    }

    fun cancel() { finished = true }
}
