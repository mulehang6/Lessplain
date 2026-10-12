package com.lessplain.ide

import com.lessplain.core.BackgroundResource
import com.lessplain.core.BackgroundTasks
import com.lessplain.core.CoalescingBackgroundWrite
import com.lessplain.model.BrowseState
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.UnderstandingRepository
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** 仓库初始化和持久化只在后台发生，界面导航只读已加载的不可变版本。 */
internal class UnderstandingStorage(
    root: Path,
    private val tasks: BackgroundTasks,
    private val changed: () -> Unit,
    private val failed: (Throwable) -> Unit,
) : AutoCloseable {
    private val resource = BackgroundResource { UnderstandingRepository(root) }
    private val records = ConcurrentHashMap<String, UnderstandingRecord>()
    private val browses = ConcurrentHashMap<String, BrowseState>()
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    var readOnly = true
        private set
    val repository: UnderstandingRepository
        get() {
            check(!SwingUtilities.isEventDispatchThread()) { "数据库访问必须在后台执行" }
            return resource.get()
        }
    private val writer = CoalescingBackgroundWrite<String, BrowseState>(
        tasks::dispatch,
        { if (!repository.readOnly && it.recordId !in deleted) repository.saveBrowseState(it) }, failed
    )

    fun initialize() {
        tasks.dispatch({ repository.readOnly }) { result ->
            result.onSuccess { readOnly = it }.onFailure(failed)
            changed()
        }
    }

    fun remember(record: UnderstandingRecord) {
        records.compute(record.id) { id, old ->
            if (id in deleted) null else if (old == null || old.revision <= record.revision) record else old
        }
    }

    fun record(id: String): UnderstandingRecord? = records[id]

    fun loadRecord(id: String): UnderstandingRecord = repository.getRecord(id).also(::remember)

    fun browse(id: String): BrowseState? = browses[id]

    fun loadBrowse(id: String): BrowseState = repository.getBrowseState(id) ?: BrowseState(id)

    fun rememberBrowse(id: String, state: BrowseState) {
        browses.compute(id) { key, old -> if (key in deleted) null else old ?: state }
    }

    fun saveBrowse(state: BrowseState) {
        browses[state.recordId] = state
        if (!readOnly) writer.submit(state.recordId, state)
    }

    fun remove(ids: Set<String>) {
        deleted.addAll(ids)
        ids.forEach { records.remove(it); browses.remove(it) }
    }

    override fun close() {
        writer.close()
        resource.close()
        records.clear()
        browses.clear()
    }
}
