package com.master.understanding.ui

import com.master.understanding.diagram.DiagramCluster
import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord

/** Each question owns exactly one visual group, independent of graph data. */
internal fun questionClusterMapping(question: UnderstandingQuestion, child: UnderstandingRecord,
    prefix: String): List<Pair<DiagramCluster, SourceCluster>> = listOf(
    DiagramCluster(question.id, child.displayTitle ?: question.text,
        child.graph.nodes.mapTo(linkedSetOf()) { prefix + it.id }) to SourceCluster(child),
)
