package com.master.lessplain.ui

import com.master.lessplain.diagram.DiagramCluster
import com.master.lessplain.model.UnderstandingQuestion
import com.master.lessplain.model.UnderstandingRecord

/** Each question owns exactly one visual group, independent of graph data. */
internal fun questionClusterMapping(question: UnderstandingQuestion, child: UnderstandingRecord,
    prefix: String): List<Pair<DiagramCluster, SourceCluster>> = listOf(
    DiagramCluster(question.id, child.displayTitle ?: question.text,
        child.graph.nodes.mapTo(linkedSetOf()) { prefix + it.id }) to SourceCluster(child),
)
