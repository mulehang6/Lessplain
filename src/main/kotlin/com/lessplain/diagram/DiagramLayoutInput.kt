package com.lessplain.diagram

import java.awt.Font
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.branchNodeIds

/** 调用 ELK 前准备已测量且不依赖界面工具包的输入。 */
internal data class DiagramLayoutInput(
    val graph: UnderstandingGraph,
    val direction: LayoutDirection,
    val clusters: List<DiagramCluster>,
    val examplePanels: List<DiagramExamplePanel>,
    val nodeSizes: Map<String, Pair<Double, Double>>,
    val edgeLabelSizes: Map<String, Pair<Double, Double>>,
    val nodeFont: Font = DiagramTextMetrics.font(),
) {
    companion object {
        fun prepare(graph: UnderstandingGraph, direction: LayoutDirection,
            clusters: List<DiagramCluster> = emptyList(),
            examplePanels: List<DiagramExamplePanel> = emptyList()): DiagramLayoutInput {
            val questionIndex = graph.edges.isEmpty() && graph.nodes.all { it.kind == "question" }
            val branches = graph.branchNodeIds()
            val font = DiagramTextMetrics.font()
            return DiagramLayoutInput(graph, direction, clusters, examplePanels,
                graph.nodes.associate { it.id to DiagramTextMetrics.nodeSize(it.label, questionIndex, it.id in branches, font) },
                graph.edges.mapNotNull { edge ->
                    edge.condition?.takeIf(String::isNotBlank)?.let { condition ->
                        edge.id to DiagramTextMetrics.edgeLabelSize(condition)
                    }
                }.toMap(),
                nodeFont = font,
            )
        }
    }
}
