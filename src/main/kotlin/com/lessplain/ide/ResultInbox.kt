package com.lessplain.ide

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/** 项目内的结果目标跨 IDE 重启保留，不依赖插件显示名。 */
internal data class PendingResult(
    val key: String,
    val recordId: String?,
    val questionId: String?,
    val createdAt: Long,
    val notified: Boolean = false,
    val isUpdate: Boolean = false,
)

internal enum class ResultDelivery { OPEN, WAIT, NOTIFY }

internal fun resultDelivery(toolWindowVisible: Boolean, projectWindowActive: Boolean): ResultDelivery = when {
    toolWindowVisible && projectWindowActive -> ResultDelivery.OPEN
    toolWindowVisible -> ResultDelivery.WAIT
    else -> ResultDelivery.NOTIFY
}

internal fun resultNotificationTitle(results: List<PendingResult>): String = when {
    results.all { it.isUpdate } -> "理解图已更新"
    results.none { it.isUpdate } -> "新增理解图"
    else -> "理解图有新增和更新"
}

private data class InboxState(
    val pending: List<PendingResult> = emptyList(),
    val seenKeys: Set<String> = emptySet(),
)

internal class ResultInbox(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    constructor(project: Project) : this(
        read = { PropertiesComponent.getInstance(project).getValue(PROPERTY_KEY) },
        write = { PropertiesComponent.getInstance(project).setValue(PROPERTY_KEY, it) },
    )

    private val gson = Gson()
    private val stateType = object : TypeToken<InboxState>() {}.type

    private fun state(): InboxState = runCatching {
        gson.fromJson<InboxState>(read(), stateType) ?: InboxState()
    }.getOrDefault(InboxState())

    @Synchronized
    fun pending(): List<PendingResult> = state().pending

    @Synchronized
    fun register(result: PendingResult): Boolean {
        val current = state()
        if (result.key in current.seenKeys) return false
        write(gson.toJson(current.copy(pending = current.pending + result,
            seenKeys = current.seenKeys + result.key)))
        return true
    }

    @Synchronized
    fun markNotified(keys: Set<String>) {
        val current = state()
        if (current.pending.none { it.key in keys && !it.notified }) return
        write(gson.toJson(current.copy(pending = current.pending.map {
            if (it.key in keys) it.copy(notified = true) else it
        })))
    }

    @Synchronized
    fun markViewed(key: String) {
        val current = state()
        if (current.pending.none { it.key == key }) return
        write(gson.toJson(current.copy(pending = current.pending.filterNot { it.key == key })))
    }

    @Synchronized
    fun removeDeleted(recordIds: Set<String>, questionIds: Set<String>) {
        val current = state()
        val remaining = current.pending.filterNot { it.recordId in recordIds || it.questionId in questionIds }
        if (remaining.size != current.pending.size) write(gson.toJson(current.copy(pending = remaining)))
    }

    private companion object {
        const val PROPERTY_KEY = "code.comprehension.results.v1"
    }
}
