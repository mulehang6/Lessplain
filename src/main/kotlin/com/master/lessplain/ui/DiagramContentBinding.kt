package com.master.lessplain.ui

import com.master.lessplain.diagram.UnderstandingDiagram
import com.master.lessplain.diagram.setDraftNoteNodes
import com.master.lessplain.ide.UnderstandingService
import com.master.lessplain.model.UnderstandingRecord

/** Question-group provenance always resolves a real child record. */
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
