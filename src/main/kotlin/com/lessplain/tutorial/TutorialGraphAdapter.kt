package com.lessplain.tutorial

import com.lessplain.model.Evidence
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph

/** 结构化工具及两种画布宿主共享图模型，源码引用先由插件解析。 */
internal fun TutorialGraph.toUnderstanding(source: (String) -> com.lessplain.model.SourceAnchor = { error("未提供源码解析器：$it") }): UnderstandingGraph = UnderstandingGraph(
    nodes = nodes.map { GraphNode(it.id, it.label, it.kind, it.sourceId?.let(source), it.evidenceIds, it.flowRoles, it.endOutcome) },
    edges = edges.map { GraphEdge(it.id, it.from, it.to, it.relation, it.condition, it.coverage, it.evidenceIds, it.inferred, it.label.ifBlank { null }) },
    evidence = evidence.map { Evidence(it.id, source(it.sourceId), it.note, it.dependencySourceIds.map(source)) },
    clusters = clusters.map { com.lessplain.model.GraphCluster(it.id, it.label, it.nodeIds) },
)
