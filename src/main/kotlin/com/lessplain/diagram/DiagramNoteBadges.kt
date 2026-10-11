package com.lessplain.diagram

/** 草稿气泡仅属于当前画布，不增加已保存的待答问题数。 */
internal fun UnderstandingDiagram.setDraftNoteNodes(nodes: Set<String>) {
    if (disposed) return
    draftNoteNodes = nodes.intersect(nodeIds)
    repaint()
}

/** 绘制和指针命中对已保存注释及临时草稿使用一致规则。 */
internal fun UnderstandingDiagram.hasNodeNote(nodeId: String): Boolean =
    (pendingNoteCounts[nodeId] ?: 0) > 0 || nodeId in draftNoteNodes
