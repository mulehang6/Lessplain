package com.master.understanding.diagram

import com.master.understanding.model.UnderstandingRecord

internal fun createUnderstandingDiagram(
    record: UnderstandingRecord,
    initialState: com.master.understanding.model.BrowseState? = null,
    clusters: List<DiagramCluster> = emptyList(),
    examplePanels: List<DiagramExamplePanel> = emptyList(),
): UnderstandingDiagram = UnderstandingDiagram(record, initialState, clusters, examplePanels)
