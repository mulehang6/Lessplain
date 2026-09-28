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
import com.master.understanding.diagram.saveGraphViewState
import com.master.understanding.ide.GraphLayerKey
import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepResult
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.GraphNode
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord
import org.jetbrains.jewel.ui.component.Text

internal data class FollowUpLayer(val recordId: String, val nodeId: String)

internal data class SourceNode(val record: UnderstandingRecord, val nodeId: String)

internal data class QuestionCanvas(
    val record: UnderstandingRecord,
    val clusters: List<DiagramCluster>,
    val sources: Map<String, SourceNode>,
    val questionCounts: Map<String, Int>,
    val examplePanels: List<DiagramExamplePanel>,
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
    val title = record.graph.nodes.firstOrNull { it.id == nodeId }?.label ?: "节点"
    val contentSignature = remember(record.id, nodeId, revision) {
        questions.map { question ->
            val child = service.repository.recordForQuestion(question.id)
            listOf(question.id, question.status, child?.id.orEmpty(), child?.revision.toString(),
                service.repository.answersForQuestion(question.id).lastOrNull()?.id.orEmpty()).joinToString(":")
        }
    }
    val canvas = remember(record.id, nodeId, contentSignature) {
        buildQuestionCanvas(service, record, nodeId, questions, revision)
    }
    val diagram = remember(canvas) {
        createUnderstandingDiagram(canvas.record, graphBrowseState(service, graphKey),
            clusters = canvas.clusters, examplePanels = canvas.examplePanels).apply {
            bindGraphState(service, graphKey)
        }
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
            StaticExplorer(canvas.record.graph, saved?.staticPath.orEmpty(), saved?.nodeId)
        }
        diagram.onViewportChanged {
            saveGraphViewState(service, graphKey, diagram, explorer?.path.orEmpty(), explorer?.currentNodeId)
        }
        var choices = emptyList<GraphEdge>()
        fun updatePath() {
            val current = explorer ?: return
            diagram.setChoiceTargets(choices.mapTo(mutableSetOf()) { it.to })
            diagram.showUnderstandingPath(current.path, current.currentNodeId, centerNode = true)
            diagram.setStepAvailability(current.path.isNotEmpty(), current.availableEdges().isNotEmpty())
            saveGraphViewState(service, graphKey, diagram, current.path, current.currentNodeId)
            canvas.sources[current.currentNodeId]?.let {
                service.select(it.record, it.nodeId, navigateSource = !diagram.navigationPinned)
            }
        }
        diagram.setStepActions(
            previous = explorer?.let { current ->
                { if (current.back()) { choices = emptyList(); updatePath() } }
            },
            next = explorer?.let { current ->
                {
                    when (val result = current.next()) {
                        is StepResult.Choose -> { choices = result.edges; diagram.setChoiceTargets(choices.mapTo(mutableSetOf()) { it.to }) }
                        is StepResult.Moved -> { choices = emptyList(); updatePath() }
                        else -> Unit
                    }
                }
            },
        )
        diagram.setStepAvailability(explorer?.path?.isNotEmpty() == true,
            explorer?.availableEdges()?.isNotEmpty() == true)
        val selection = diagram.onNodeSelected { id ->
            val current = explorer
            if (current != null) {
                val chosen = choices.firstOrNull { it.to == id }
                if (chosen != null) current.choose(chosen.id) else current.jumpTo(id)
                choices = emptyList()
                updatePath()
            } else canvas.sources[id]?.let {
                service.select(it.record, it.nodeId, navigateSource = !diagram.navigationPinned)
            }
        }
        val sourceSelection = service.onSelectionChanged { recordId, nodeId ->
            val id = canvas.sources.entries.firstOrNull { (_, source) ->
                source.record.id == recordId && source.nodeId == nodeId
            }?.key
            val current = explorer
            if (id != null && current != null && id != current.currentNodeId && current.jumpTo(id)) {
                choices = emptyList()
                diagram.setChoiceTargets(emptySet())
                diagram.showUnderstandingPath(current.path, id, centerNode = true)
                diagram.setStepAvailability(current.path.isNotEmpty(), current.availableEdges().isNotEmpty())
                saveGraphViewState(service, graphKey, diagram, current.path, id)
            }
        }
        val copy = diagram.onCopyNodeContext { id ->
            canvas.sources[id]?.let {
                service.copyNodeContext(it.record, it.nodeId)
                diagram.showCopied(id)
            }
        }
        val questionSelection = diagram.onQuestionBadgeClicked { id ->
            canvas.sources[id]?.takeIf { questionsForNode(service, it.record, it.nodeId).isNotEmpty() }?.let {
                onChildQuestions(it.record, it.nodeId)
            }
        }
        val clusterSelection = diagram.onClusterSelected { id ->
            explorer?.jumpTo(id)
            choices = emptyList()
            diagram.setChoiceTargets(emptySet())
            explorer?.let { diagram.showUnderstandingPath(it.path, id, centerNode = false) }
            diagram.setStepAvailability(explorer?.path?.isNotEmpty() == true,
                explorer?.availableEdges()?.isNotEmpty() == true)
            explorer?.let { saveGraphViewState(service, graphKey, diagram, it.path, it.currentNodeId) }
            canvas.sources[id]?.let {
                service.select(it.record, it.nodeId, navigateSource = !diagram.navigationPinned)
            }
        }
        onDispose {
            saveGraphViewState(service, graphKey, diagram, explorer?.path.orEmpty(), explorer?.currentNodeId)
            selection.close()
            sourceSelection.close()
            copy.close()
            questionSelection.close()
            clusterSelection.close()
            diagram.setStepActions(null, null)
            Disposer.dispose(diagram)
        }
    }
    DisposableEffect(diagram, liveQuestionCounts) {
        diagram.setQuestionCounts(liveQuestionCounts)
        onDispose { }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$title · ${questions.size} 个追问")
        key(diagram) {
            SwingPanel(factory = { diagram.jComponent }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

internal fun questionsForNode(
    service: UnderstandingService, record: UnderstandingRecord, nodeId: String,
): List<UnderstandingQuestion> = service.repository.questionsForRecord(record.id).filter { question ->
    service.repository.getContext(question.contextToken).nodeId == nodeId
}

internal fun buildQuestionCanvas(
    service: UnderstandingService,
    owner: UnderstandingRecord,
    ownerNodeId: String,
    questions: List<UnderstandingQuestion>,
    revision: Int,
): QuestionCanvas {
    val nodes = mutableListOf<GraphNode>()
    val edges = mutableListOf<com.master.understanding.model.GraphEdge>()
    val evidence = mutableListOf<com.master.understanding.model.Evidence>()
    val clusters = mutableListOf<DiagramCluster>()
    val sources = linkedMapOf<String, SourceNode>()
    val counts = linkedMapOf<String, Int>()
    val panels = mutableListOf<DiagramExamplePanel>()
    questions.forEach { question ->
        val child = service.repository.recordForQuestion(question.id)
        val prefix = "q${question.id}_"
        if (child == null) {
            val id = "${prefix}answer"
            val answered = service.repository.answersForQuestion(question.id).isNotEmpty()
            nodes += GraphNode(id, if (answered) "已有文字回答\n尚无示例图" else "等待回答", kind = "placeholder")
            sources[id] = SourceNode(owner, ownerNodeId)
            clusters += DiagramCluster(question.id, question.text, setOf(id))
        } else {
            val ids = child.graph.nodes.mapTo(linkedSetOf()) { prefix + it.id }
            child.graph.nodes.forEach { node ->
                val id = prefix + node.id
                nodes += node.copy(id = id, evidenceIds = node.evidenceIds.map { prefix + it })
                sources[id] = SourceNode(child, node.id)
            }
            child.graph.edges.forEach { edge ->
                edges += edge.copy(id = prefix + edge.id, from = prefix + edge.from, to = prefix + edge.to,
                    evidenceIds = edge.evidenceIds.map { prefix + it })
            }
            child.graph.evidence.forEach { item -> evidence += item.copy(id = prefix + item.id) }
            service.repository.questionsForRecord(child.id).forEach { nested ->
                val target = service.repository.getContext(nested.contextToken).nodeId
                if (target != null) counts[prefix + target] = (counts[prefix + target] ?: 0) + 1
            }
            clusters += DiagramCluster(question.id, question.text, ids)
            examplePanel(question, child, prefix)?.let(panels::add)
        }
    }
    return QuestionCanvas(
        UnderstandingRecord("questions:${owner.id}:$ownerNodeId", revision, owner.id, null,
            owner.graph.nodes.firstOrNull { it.id == ownerNodeId }?.label ?: "追问", "", null,
            UnderstandingGraph(nodes, edges, evidence), owner.createdAt),
        clusters, sources, counts, panels,
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
