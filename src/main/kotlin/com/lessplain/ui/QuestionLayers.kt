package com.lessplain.ui

import com.lessplain.diagram.DiagramCluster
import com.lessplain.diagram.DiagramExamplePanel
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.GraphNode
import com.lessplain.model.GraphEdge
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingQuestion
import com.lessplain.model.UnderstandingRecord

internal data class FollowUpLayer(val recordId: String, val nodeId: String)

internal data class SourceNode(val record: UnderstandingRecord, val nodeId: String)

internal data class CanvasSourceEdge(val record: UnderstandingRecord, val edgeId: String)

internal data class QuestionCanvas(
    val record: UnderstandingRecord,
    val clusters: List<DiagramCluster>,
    val sources: Map<String, SourceNode>,
    val questionCounts: Map<String, Int>,
    val examplePanels: List<DiagramExamplePanel>,
    val clusterSources: Map<String, SourceCluster> = emptyMap(),
    val edgeSources: Map<String, CanvasSourceEdge> = emptyMap(),
)

internal fun questionsForNode(
    service: UnderstandingService, record: UnderstandingRecord, nodeId: String,
): List<UnderstandingQuestion> = service.repository.questionsForRecord(record.id).filter { question ->
    service.repository.getContext(question.contextToken).nodeId == nodeId &&
        service.repository.recordForQuestion(question.id) != null
}

internal fun buildQuestionCanvas(
    service: UnderstandingService,
    owner: UnderstandingRecord,
    ownerNodeId: String,
    questions: List<UnderstandingQuestion>,
    revision: Int,
): QuestionCanvas {
    val nodes = mutableListOf<GraphNode>()
    val edges = mutableListOf<GraphEdge>()
    val evidence = mutableListOf<com.lessplain.model.Evidence>()
    val clusters = mutableListOf<DiagramCluster>()
    val sources = linkedMapOf<String, SourceNode>()
    val counts = linkedMapOf<String, Int>()
    val panels = mutableListOf<DiagramExamplePanel>()
    val clusterSources = linkedMapOf<String, SourceCluster>()
    val edgeSources = linkedMapOf<String, CanvasSourceEdge>()
    questions.forEach { question ->
        val child = service.repository.recordForQuestion(question.id)
        if (child == null) return@forEach
        val prefix = "q${question.id}_"
        run {
            child.graph.nodes.forEach { node ->
                val id = prefix + node.id
                nodes += node.copy(id = id, evidenceIds = node.evidenceIds.map { prefix + it })
                sources[id] = SourceNode(child, node.id)
            }
            child.graph.edges.forEach { edge ->
                val canvasEdgeId = prefix + edge.id
                edges += edge.copy(id = canvasEdgeId, from = prefix + edge.from, to = prefix + edge.to,
                    evidenceIds = edge.evidenceIds.map { prefix + it })
                edgeSources[canvasEdgeId] = CanvasSourceEdge(child, edge.id)
            }
            child.graph.evidence.forEach { item -> evidence += item.copy(id = prefix + item.id) }
            service.repository.questionsForRecord(child.id).forEach { nested ->
                val target = service.repository.getContext(nested.contextToken).nodeId
                if (target != null) counts[prefix + target] = (counts[prefix + target] ?: 0) + 1
            }
            questionClusterMapping(question, child, prefix).forEachIndexed { index, (cluster, source) ->
                clusters += cluster
                clusterSources[cluster.id] = source
                if (index == 0) examplePanel(question, child, prefix)?.copy(clusterId = cluster.id)?.let(panels::add)
            }
        }
    }
    return QuestionCanvas(
        UnderstandingRecord("questions:${owner.id}:$ownerNodeId", revision, owner.id, null,
            owner.graph.nodes.firstOrNull { it.id == ownerNodeId }?.label ?: "追问", "", null,
            UnderstandingGraph(nodes, edges, evidence), owner.createdAt),
        clusters, sources, counts, panels, clusterSources, edgeSources,
    )
}

/** 可复用的请求参数示例，明确将值标为假设。 */
private fun examplePanel(question: UnderstandingQuestion, child: UnderstandingRecord, prefix: String): DiagramExamplePanel? {
    val description = question.text + " " + child.graph.nodes.joinToString(" ") { it.label }
    val parameter = Regex("getParameter\\s*[（(]\\s*[\"']([^\"']+)[\"']\\s*[）)]").find(description)?.groupValues?.get(1)
        ?: return null
    val sample = child.graph.nodes.asSequence().mapNotNull { node ->
        Regex("${Regex.escape(parameter)}=([^\\s]+)").find(node.label)?.groupValues?.get(1)
    }.firstOrNull() ?: "alice"
    val missing = child.graph.nodes.filter { "null" in it.label || "缺失" in it.label || "没有" in it.label }
        .mapTo(mutableSetOf()) { prefix + it.id }
    val focus = child.graph.nodes.associate { node ->
        val field = when {
            "赋" in node.label || "局部变量" in node.label -> "variable"
            "得到" in node.label || "结果" in node.label -> "result"
            else -> "parameter"
        }
        prefix + node.id to field
    }
    return DiagramExamplePanel(question.id, parameter, sample, missing, focus)
}
