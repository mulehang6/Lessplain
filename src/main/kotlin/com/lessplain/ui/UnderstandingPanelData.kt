package com.lessplain.ui

import com.lessplain.ide.PanelPage
import com.lessplain.model.BrowseState
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.UnderstandingRepository

/** 在界面线程捕获加载参数，后台读取不接触 Service 的活动选择。 */
internal data class UnderstandingPanelRequest(
    val page: PanelPage,
    val activeRecordId: String?,
    val rootRecordId: String?,
    val layers: List<FollowUpLayer>,
    val search: String,
    val historyPage: Int = 0,
)

internal data class UnderstandingPanelData(
    val selected: UnderstandingRecord?,
    val displayed: UnderstandingRecord?,
    val history: List<UnderstandingRecord>,
    val hierarchyRootId: String?,
    val hierarchy: List<GraphHierarchyEntry>,
    val initialBrowse: BrowseState?,
    val historyHasNext: Boolean = false,
    val historyUnread: Map<String, com.lessplain.ide.PendingResult> = emptyMap(),
)

/** 图表页不读取历史列表；全部数据库工作在后台调用本函数。 */
internal fun readUnderstandingPanelData(
    repository: UnderstandingRepository,
    request: UnderstandingPanelRequest,
    pending: List<com.lessplain.ide.PendingResult> = emptyList(),
): UnderstandingPanelData {
    val selected = request.activeRecordId?.let { com.lessplain.core.cancellationAwareResult { repository.getRecord(it) }.getOrNull() }
    val layer = request.layers.lastOrNull()
    val displayed = if (layer == null) selected else if (layer.recordId == selected?.id) selected
        else repository.getRecord(layer.recordId)
    val history = if (request.page == PanelPage.HISTORY || displayed == null) repository.recentRootRecords(request.search, HISTORY_PAGE_SIZE + 1, request.historyPage * HISTORY_PAGE_SIZE) else emptyList()
    val rootId = request.rootRecordId ?: if (request.layers.isEmpty()) displayed?.id else null
    val hierarchy = if (displayed != null && request.page != PanelPage.HISTORY)
        graphHierarchyEntries(repository, rootId) else emptyList()
    val initialBrowse = if (displayed != null && request.page == PanelPage.CANVAS && layer == null)
        repository.getBrowseState(displayed.id) else null
    val unread = if (request.page == PanelPage.HISTORY) historyUnreadResults(repository, history.take(HISTORY_PAGE_SIZE).map { it.id }.toSet(), pending) else emptyMap()
    return UnderstandingPanelData(selected, displayed, history.take(HISTORY_PAGE_SIZE), rootId, hierarchy, initialBrowse, history.size > HISTORY_PAGE_SIZE, unread)
}

internal const val HISTORY_PAGE_SIZE = 100

/** 未读回答归入所属根记录，打开条目仍定位具体回答并逐项清除未读。 */
internal fun historyUnreadResults(repository: UnderstandingRepository, roots: Set<String>, pending: List<com.lessplain.ide.PendingResult>) =
    pending.sortedByDescending { it.createdAt }.mapNotNull { result ->
        com.lessplain.core.cancellationAwareResult {
            com.lessplain.ide.loadResultLocation(repository, result)?.let { location ->
                (location.rootRecordId ?: location.record.id).takeIf { it in roots }?.let { it to result }
            }
        }.getOrNull()
    }.distinctBy { it.first }.toMap()
