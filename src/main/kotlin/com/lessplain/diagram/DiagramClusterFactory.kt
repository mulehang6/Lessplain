package com.lessplain.diagram

import com.lessplain.model.UnderstandingRecord

internal fun createUnderstandingDiagram(
    record: UnderstandingRecord,
    initialState: com.lessplain.model.BrowseState? = null,
    clusters: List<DiagramCluster> = if (record.parentId == null) record.graph.clusters
        .map { DiagramCluster(it.id, it.title, it.nodeIds.toSet()) } else emptyList(),
    examplePanels: List<DiagramExamplePanel> = emptyList(),
): UnderstandingDiagram = UnderstandingDiagram(record, initialState, clusters, examplePanels)
