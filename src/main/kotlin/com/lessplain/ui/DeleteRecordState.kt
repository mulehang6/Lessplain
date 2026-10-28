package com.lessplain.ui

/** 即使确认期间历史选择改变，操作目标也保持不变。 */
internal class DeleteRecordState(val recordId: String, val title: String, val kind: String = "理解图",
    val description: String = "永久删除这张理解图及其全部追问和回答？此操作无法撤销。") {
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
