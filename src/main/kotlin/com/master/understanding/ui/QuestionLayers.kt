package com.master.understanding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import com.intellij.openapi.util.Disposer
import com.master.understanding.diagram.DiagramCluster
import com.master.understanding.diagram.DiagramHierarchyItem
import com.master.understanding.diagram.DiagramExamplePanel
import com.master.understanding.diagram.createUnderstandingDiagram
import com.master.understanding.diagram.bindGraphState
import com.master.understanding.diagram.graphBrowseState
import com.master.understanding.diagram.requestFocusCluster
import com.master.understanding.diagram.bindDiagramSourceNavigation
import com.master.understanding.ide.GraphLayerKey
import com.master.understanding.core.StaticExplorer
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.GraphNode
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.storage.pendingQuestionsForNode

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

/** One node's questions share a single canvas, with one navigable cluster per question. */
@Composable
internal fun QuestionIndexView(
    service: UnderstandingService,
    record: UnderstandingRecord,
    nodeId: String,
    revision: Int,
    modifier: Modifier,
    hierarchy: List<DiagramHierarchyItem>,
    onNavigateIndex: (Int) -> Unit,
    onChildQuestions: (UnderstandingRecord, String) -> Unit,
) {
    val graphKey = GraphLayerKey(record.id, nodeId)
    val questions = remember(record.id, nodeId, revision) { questionsForNode(service, record, nodeId) }
    val contentSignature = remember(record.id, nodeId, revision) {
        questions.map { question ->
            val child = service.repository.recordForQuestion(question.id)
            listOf(question.id, child?.id.orEmpty(), child?.revision.toString()).joinToString(":")
        }
    }
    val canvas = remember(record.id, nodeId, contentSignature) {
        buildQuestionCanvas(service, record, nodeId, questions, revision)
    }
    val diagram = remember(canvas) {
        createUnderstandingDiagram(canvas.record, graphBrowseState(service, graphKey),
            clusters = canvas.clusters, examplePanels = canvas.examplePanels).apply {
            bindGraphState(service.graphStates, graphKey)
        }
    }
    DisposableEffect(diagram, service.panelFocusQuestionId) {
        service.panelFocusQuestionId?.let { questionId ->
            canvas.clusters.firstOrNull { cluster ->
                cluster.id == questionId || canvas.clusterSources[cluster.id]?.record?.questionId == questionId
            }?.id?.let(diagram::requestFocusCluster)
        }
        onDispose { }
    }
    DisposableEffect(diagram, hierarchy, onNavigateIndex) {
        diagram.setHierarchy(hierarchy, onNavigateIndex)
        onDispose { }
    }
    val liveQuestionCounts = remember(canvas, revision) {
        canvas.sources.mapNotNull { (id, source) ->
            if (source.record.id == record.id) return@mapNotNull null
            val count = questionsForNode(service, source.record, source.nodeId).size
            id.takeIf { count > 0 }?.let { it to count }
        }.toMap()
    }
    DisposableEffect(diagram, canvas) {
        val saved = graphBrowseState(service, graphKey)
        val explorer = canvas.record.graph.nodes.firstOrNull()?.let {
            StaticExplorer(canvas.record.graph, saved?.staticPath.orEmpty(), saved?.nodeId, saved?.navigationState,
                scopes = canvas.sources.mapValues { it.value.record.id })
        }
        val navigation = bindDiagramSourceNavigation(service, graphKey, diagram, explorer, record, canvas)
        val copy = diagram.onCopyNodeContext { id ->
            canvas.sources[id]?.let {
                service.copyNodeContext(it.record, it.nodeId)
                diagram.showCopied(id)
            }
        }
        val content = diagram.bindContentActions(service, canvas.sources::get, canvas.clusterSources::get)
        val questionSelection = diagram.onQuestionBadgeClicked { id ->
            canvas.sources[id]?.takeIf { questionsForNode(service, it.record, it.nodeId).isNotEmpty() }?.let {
                onChildQuestions(it.record, it.nodeId)
            }
        }
        onDispose {
            navigation.close()
            copy.close()
            content.close()
            questionSelection.close()
            Disposer.dispose(diagram)
        }
    }
    DisposableEffect(diagram, liveQuestionCounts) {
        diagram.setQuestionCounts(liveQuestionCounts)
        onDispose { }
    }
    DisposableEffect(diagram, revision, canvas) {
        diagram.setPendingNoteCounts(canvas.sources.mapNotNull { (id, source) ->
            val count = service.repository.pendingQuestionsForNode(source.record.id, source.nodeId).size
            id.takeIf { count > 0 }?.let { it to count }
        }.toMap())
        onDispose { }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        key(diagram) {
            SwingPanel(factory = { diagram.jComponent }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

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
    val evidence = mutableListOf<com.master.understanding.model.Evidence>()
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

/** A reusable request-parameter example; values are explicitly hypothetical. */
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
