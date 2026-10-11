package com.lessplain.ui

import com.lessplain.diagram.DiagramCluster
import com.lessplain.model.UnderstandingQuestion
import com.lessplain.model.UnderstandingRecord

/** 每个问题恰好拥有一个可视分组，不依赖图数据。 */
internal fun questionClusterMapping(question: UnderstandingQuestion, child: UnderstandingRecord,
    prefix: String): List<Pair<DiagramCluster, SourceCluster>> = listOf(
    DiagramCluster(question.id, child.displayTitle ?: question.text,
        child.graph.nodes.mapTo(linkedSetOf()) { prefix + it.id }) to SourceCluster(child),
)
