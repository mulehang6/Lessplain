package com.master.understanding.diagram

import com.master.understanding.model.UnderstandingGraph

/** Measured, toolkit-neutral input prepared before invoking ELK. */
internal data class DiagramLayoutInput(
    val graph: UnderstandingGraph,
    val direction: LayoutDirection,
    val clusters: List<DiagramCluster>,
    val examplePanels: List<DiagramExamplePanel>,
    val nodeSizes: Map<String, Pair<Double, Double>>,
    val edgeLabelSizes: Map<String, Pair<Double, Double>>,
) {
    companion object {
        fun prepare(graph: UnderstandingGraph, direction: LayoutDirection,
            clusters: List<DiagramCluster> = emptyList(),
            examplePanels: List<DiagramExamplePanel> = emptyList()): DiagramLayoutInput {
            val questionIndex = graph.edges.isEmpty() && graph.nodes.all { it.kind == "question" }
            return DiagramLayoutInput(graph, direction, clusters, examplePanels,
                graph.nodes.associate { it.id to DiagramTextMetrics.nodeSize(it.label, questionIndex) },
                graph.edges.mapNotNull { edge ->
                    edge.condition?.takeIf(String::isNotBlank)?.let { condition ->
                        edge.id to DiagramTextMetrics.edgeLabelSize(condition)
                    }
                }.toMap(),
            )
        }
    }
}
