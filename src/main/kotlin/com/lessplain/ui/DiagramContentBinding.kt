package com.lessplain.ui

import com.lessplain.diagram.UnderstandingDiagram
import com.lessplain.diagram.setDraftNoteNodes
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.UnderstandingRecord

/** 追问组的来源始终解析为真实子记录。 */
internal data class SourceCluster(val record: UnderstandingRecord)

internal fun UnderstandingDiagram.bindContentActions(
    service: UnderstandingService,
    source: (String) -> SourceNode?,
    clusterSource: (String) -> SourceCluster?,
    titleRecord: UnderstandingRecord? = null,
): AutoCloseable {
    val actions = service.contentActions
    val subscriptions = listOf(
        header.bindEdit(titleRecord?.let { record ->
            { actions.editRecordTitle(record.id, this, header.popupAnchor) }
        }),
        onAddNodeNote { id -> source(id)?.let { actions.addNodeNote(it.record, it.nodeId, this, overlays.anchor) } },
        onNoteBadgeClicked { id, component, point -> source(id)?.let {
            actions.showNodeNotes(it.record, it.nodeId, component, point)
        } },
        actions.onDraftNotesChanged(this) { sourceNodes ->
            val localNodes = nodeIds.filterTo(linkedSetOf()) { localId ->
                source(localId)?.let { (it.record.id to it.nodeId) in sourceNodes } == true
            }
            setDraftNoteNodes(localNodes)
        },
        if (titleRecord?.graph?.clusters.isNullOrEmpty()) onClusterTitleEdit { id -> clusterSource(id)?.let {
            actions.editRecordTitle(it.record.id, this, overlays.anchor)
        } } else AutoCloseable { },
    )
    return AutoCloseable { subscriptions.forEach(AutoCloseable::close) }
}
