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
)

internal data class UnderstandingPanelData(
    val selected: UnderstandingRecord?,
    val displayed: UnderstandingRecord?,
    val recent: List<UnderstandingRecord>,
    val history: List<UnderstandingRecord>,
    val hierarchyRootId: String?,
    val hierarchy: List<GraphHierarchyEntry>,
    val initialBrowse: BrowseState?,
)

/** 图表页不读取历史列表；全部数据库工作在后台调用本函数。 */
internal fun readUnderstandingPanelData(
    repository: UnderstandingRepository,
    request: UnderstandingPanelRequest,
): UnderstandingPanelData {
    val selected = request.activeRecordId?.let { runCatching { repository.getRecord(it) }.getOrNull() }
    val layer = request.layers.lastOrNull()
    val displayed = if (layer == null) selected else if (layer.recordId == selected?.id) selected
        else repository.getRecord(layer.recordId)
    val welcome = request.page == PanelPage.WELCOME || (selected == null && request.layers.isEmpty() &&
        request.page != PanelPage.HISTORY)
    val history = if (request.page == PanelPage.HISTORY) repository.recentRootRecords(request.search, 500) else emptyList()
    val recent = if (welcome) repository.recentRootRecords("", 6) else emptyList()
    val rootId = request.rootRecordId ?: if (request.layers.isEmpty()) displayed?.id else null
    val hierarchy = if (!welcome && request.page != PanelPage.HISTORY)
        graphHierarchyEntries(repository, rootId) else emptyList()
    val initialBrowse = if (!welcome && request.page == PanelPage.CANVAS && layer == null)
        displayed?.let { repository.getBrowseState(it.id) } else null
    return UnderstandingPanelData(selected, displayed, recent, history, rootId, hierarchy, initialBrowse)
}
