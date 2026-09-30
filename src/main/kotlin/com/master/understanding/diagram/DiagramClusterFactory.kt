package com.master.understanding.diagram

import com.master.understanding.model.UnderstandingRecord

/** A root graph uses the same canvas treatment as a question graph. */
internal fun rootDiagramCluster(record: UnderstandingRecord): DiagramCluster = DiagramCluster(
    id = "root:${record.id}",
    title = record.displayTitle ?: record.question,
    nodeIds = record.graph.nodes.mapTo(linkedSetOf()) { it.id },
)

internal fun createUnderstandingDiagram(
    record: UnderstandingRecord,
    initialState: com.master.understanding.model.BrowseState? = null,
    clusters: List<DiagramCluster> = emptyList(),
    examplePanels: List<DiagramExamplePanel> = emptyList(),
): UnderstandingDiagram = UnderstandingDiagram(record, initialState, clusters, examplePanels)
