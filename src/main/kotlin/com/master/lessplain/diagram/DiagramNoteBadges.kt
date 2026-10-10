package com.master.lessplain.diagram

/** Draft badges belong to this canvas and never increase persisted pending-question counts. */
internal fun UnderstandingDiagram.setDraftNoteNodes(nodes: Set<String>) {
    if (disposed) return
    draftNoteNodes = nodes.intersect(nodeIds)
    repaint()
}

/** Painting and pointer hit testing agree on saved notes and transient drafts. */
internal fun UnderstandingDiagram.hasNodeNote(nodeId: String): Boolean =
    (pendingNoteCounts[nodeId] ?: 0) > 0 || nodeId in draftNoteNodes
