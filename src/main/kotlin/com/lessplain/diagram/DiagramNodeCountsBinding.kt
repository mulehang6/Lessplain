package com.lessplain.diagram

import com.lessplain.core.LatestBackgroundRead
import com.lessplain.ide.UnderstandingService
import com.lessplain.storage.NodeQuestionCounts
import com.lessplain.storage.NodeQuestionKey
import com.lessplain.storage.nodeQuestionCounts
import com.lessplain.ui.SourceNode

/** 根图和追问画布共用后台计数；来源映射固定，不随项目当前节点变化。 */
internal fun UnderstandingDiagram.bindNodeQuestionCounts(
    service: UnderstandingService,
    source: (String) -> SourceNode?,
    onStaleSource: () -> Unit,
): AutoCloseable {
    val sources = nodeIds.mapNotNull { localId -> source(localId)?.let { localId to it } }.toMap()
    val records = sources.values.map { it.record }.distinctBy { it.id }
    val loader = LatestBackgroundRead<NodeQuestionCounts>(service::readBackground) { result ->
        if (!disposed) result.onSuccess { counts ->
            if (records.any { counts.revisions[it.id] != it.revision }) onStaleSource()
            fun local(values: Map<NodeQuestionKey, Int>): Map<String, Int> = sources.mapNotNull { (localId, origin) ->
                values[NodeQuestionKey(origin.record.id, origin.nodeId)]?.let { localId to it }
            }.toMap()
            setQuestionCounts(local(counts.answered))
            setPendingNoteCounts(local(counts.pending))
        }
    }
    fun refresh() = loader.request {
        service.repository.nodeQuestionCounts(records.map { it.id }).also { counts ->
            records.filter { counts.revisions[it.id] != it.revision }.forEach { record ->
                com.lessplain.core.cancellationAwareResult { service.storage.loadRecord(record.id) }
            }
        }
    }
    val subscription = service.onChanged(::refresh)
    refresh()
    return AutoCloseable { subscription.close(); loader.close() }
}
