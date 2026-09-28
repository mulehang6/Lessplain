package com.master.understanding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.ApplicationManager
import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepResult
import com.master.understanding.diagram.UnderstandingDiagram
import com.master.understanding.diagram.DiagramHierarchyItem
import com.master.understanding.diagram.createUnderstandingDiagram
import com.master.understanding.diagram.bindGraphState
import com.master.understanding.diagram.graphBrowseState
import com.master.understanding.diagram.saveGraphViewState
import com.master.understanding.ide.GraphLayerKey
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.BrowseState
import com.master.understanding.model.UnderstandingRecord
import com.intellij.openapi.util.Disposer
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
fun UnderstandingPanel(service: UnderstandingService) {
    var revision by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(false) }
    var layers by remember(service) { mutableStateOf(service.panelLayers) }
    var rootRecordId by remember(service) { mutableStateOf(service.panelRootRecordId) }
    val setLayers: (List<FollowUpLayer>) -> Unit = { value ->
        layers = value
        service.panelLayers = value
    }
    val setRootRecordId: (String?) -> Unit = { value ->
        rootRecordId = value
        service.panelRootRecordId = value
    }
    SideEffect {
        service.panelLayers = layers
        service.panelRootRecordId = rootRecordId
    }
    val search = remember { TextFieldState() }
    DisposableEffect(service) {
        val subscription = service.onChanged { revision++ }
        onDispose {
            subscription.close()
        }
    }
    val recentRecords = remember(revision) { service.repository.recentRootRecords("", 6) }
    val records = remember(revision, history, search.text.toString()) {
        service.repository.recentRootRecords(if (history) search.text.toString() else "", if (history) 500 else 6)
    }
    val questions = remember(revision, history, search.text.toString()) {
        service.repository.recentQuestions(if (history) search.text.toString() else "", if (history) 500 else 6)
    }
    val selected = remember(revision, service.activeRecordId) {
        service.activeRecordId?.let { id -> runCatching { service.repository.getRecord(id) }.getOrNull() }
    }
    val looseQuestions = remember(questions, revision) {
        questions.filter { it.ownerRecordId == null && service.repository.recordForQuestion(it.id) == null }
    }
    val currentLayer = layers.lastOrNull()
    val displayedRecord = currentLayer?.let { service.repository.getRecord(it.recordId) } ?: selected
    val selectRoot: (UnderstandingRecord) -> Unit = { record ->
        setLayers(emptyList())
        setRootRecordId(null)
        service.select(record)
        history = false
    }
    val hierarchyRootId = rootRecordId ?: if (layers.isEmpty()) displayedRecord?.id else null
    val hierarchyEntries = remember(hierarchyRootId, revision) {
        graphHierarchyEntries(service.repository, hierarchyRootId)
    }
    val hierarchyItems = hierarchyEntries.mapIndexed { index, entry ->
        DiagramHierarchyItem(entry.title, entry.depth, index, entry.layers == layers)
    }
    val navigateToIndex: (Int) -> Unit = { index ->
        hierarchyEntries.getOrNull(index)?.let { destination ->
            val targetLayer = destination.layers.lastOrNull()
            val target = targetLayer?.let { service.repository.getRecord(it.recordId) }
                ?: hierarchyRootId?.let(service.repository::getRecord)
            setLayers(destination.layers)
            setRootRecordId(hierarchyRootId.takeIf { destination.layers.isNotEmpty() })
            target?.let { service.select(it, targetLayer?.nodeId,
                navigateSource = !service.graphStates.get(GraphLayerKey(it.id, targetLayer?.nodeId)).navigationPinned) }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val actionItems: @Composable () -> Unit = {
                RecentRecordsButton(recentRecords) { record ->
                    selectRoot(record)
                }
                OutlinedButton(onClick = { history = !history }) { Text(if (history) "返回图" else "历史") }
            }
            val actions: @Composable () -> Unit = {
                if (maxWidth < 260.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { actionItems() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actionItems() }
                }
            }
            if (maxWidth < 420.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Understanding")
                    actions()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Understanding", modifier = Modifier.weight(1f))
                    actions()
                }
            }
        }
        if (history) {
            Text("历史记录")
            TextField(search, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索问题和回答") })
        }
        if (service.repository.readOnly) Text("此项目已在另一 IDE 实例中打开，当前只读")
        if (layers.isNotEmpty() && !history) {
            OutlinedButton(onClick = {
                val parentLayers = layers.dropLast(1)
                val parentIndex = hierarchyEntries.indexOfFirst { it.layers == parentLayers }
                if (parentIndex >= 0) navigateToIndex(parentIndex)
            }) { Text("‹ 返回") }
        }
        if ((selected == null && layers.isEmpty()) || history) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (records.isEmpty() && looseQuestions.isEmpty()) Text("暂无理解记录")
                records.forEach { record ->
                    val count = service.repository.questionsForRecord(record.id).size
                    HistoryRecordRow(record, selected?.id == record.id, count) {
                        selectRoot(record)
                    }
                }
                if (looseQuestions.isNotEmpty()) Text("尚未归入理解图的提问")
                looseQuestions.forEach { item ->
                    val answers = service.repository.answersForQuestion(item.id)
                    HistoryQuestionRow(item, answers.map { it.answer })
                }
            }
        } else {
            when (currentLayer) {
                is FollowUpLayer -> QuestionIndexView(
                    service, requireNotNull(displayedRecord), currentLayer.nodeId, revision, Modifier.weight(1f),
                    hierarchyItems, navigateToIndex,
                ) { child, nodeId ->
                    service.select(child, nodeId, navigateSource = !service.graphStates.get(GraphLayerKey(child.id, nodeId)).navigationPinned)
                    setLayers(layers + FollowUpLayer(child.id, nodeId))
                }
                null -> RecordView(service, requireNotNull(displayedRecord), revision, Modifier.weight(1f),
                    hierarchyItems, navigateToIndex) { nodeId ->
                    setRootRecordId(displayedRecord.id)
                    service.select(displayedRecord, nodeId, navigateSource = !service.graphStates.get(GraphLayerKey(displayedRecord.id, nodeId)).navigationPinned)
                    setLayers(layers + FollowUpLayer(displayedRecord.id, nodeId))
                }
            }
        }
    }
}

@Composable
private fun RecordView(
    service: UnderstandingService,
    record: UnderstandingRecord,
    revision: Int,
    modifier: Modifier,
    hierarchy: List<DiagramHierarchyItem>,
    onNavigateIndex: (Int) -> Unit,
    onQuestions: (String) -> Unit,
) {
    val graphKey = GraphLayerKey(record.id)
    val questionCounts = remember(record.id, revision) {
        service.repository.questionsForRecord(record.id).groupingBy { question ->
            service.repository.getContext(question.contextToken).nodeId
        }.eachCount().filterKeys { it != null }.mapKeys { it.key!! }
    }
    val explorer = remember(record.id, record.revision) {
        val state = graphBrowseState(service, graphKey)
        StaticExplorer(record.graph, state?.staticPath.orEmpty(), service.activeNodeId ?: state?.nodeId)
    }
    var pathRevision by remember(record.id, record.revision) { mutableStateOf(0) }
    var choices by remember(record.id, record.revision) { mutableStateOf<List<com.master.understanding.model.GraphEdge>>(emptyList()) }
    val current = remember(pathRevision, record.id, record.revision) { explorer.currentNodeId }
    val diagram = remember(record.id, record.revision) {
        runCatching { createUnderstandingDiagram(record, graphBrowseState(service, graphKey)).apply {
            bindGraphState(service, graphKey)
        } }
    }
    DisposableEffect(diagram, hierarchy, onNavigateIndex) {
        diagram.getOrNull()?.setHierarchy(hierarchy, onNavigateIndex)
        onDispose { }
    }
    DisposableEffect(diagram) {
        diagram.getOrNull()?.onViewportChanged {
            saveGraphViewState(service, graphKey, diagram.getOrNull() ?: return@onViewportChanged,
                explorer.path, explorer.currentNodeId)
        }
        val selection = diagram.getOrNull()?.onNodeSelected { nodeId ->
            val chosen = choices.firstOrNull { it.to == nodeId }
            if (choices.isNotEmpty() && chosen == null) return@onNodeSelected
            val moved = if (chosen != null) explorer.choose(chosen.id) is StepResult.Moved else explorer.jumpTo(nodeId)
            if (moved) {
                choices = emptyList()
                pathRevision++
                persistPath(service, record, explorer)
                service.select(record, nodeId, navigateSource = diagram.getOrNull()?.navigationPinned != true)
            }
        }
        val questionSelection = diagram.getOrNull()?.onQuestionBadgeClicked(onQuestions)
        val copySelection = diagram.getOrNull()?.onCopyNodeContext { nodeId ->
            service.copyNodeContext(record, nodeId)
            diagram.getOrNull()?.showCopied(nodeId)
        }
        val synchronization = service.onSelectionChanged { recordId, nodeId ->
            if (recordId == record.id) {
                nodeId?.let {
                    if (it != explorer.currentNodeId && explorer.jumpTo(it)) {
                        pathRevision++
                        persistPath(service, record, explorer)
                    }
                    diagram.getOrNull()?.selectUnderstandingNode(it)
                }
            }
        }
        onDispose {
            selection?.close()
            questionSelection?.close()
            copySelection?.close()
            synchronization.close()
            diagram.getOrNull()?.let { builder ->
                persistView(service, record, builder, explorer)
                Disposer.dispose(builder)
            }
        }
    }
    DisposableEffect(diagram, questionCounts) {
        diagram.getOrNull()?.setQuestionCounts(questionCounts)
        onDispose { }
    }
    DisposableEffect(diagram, choices) {
        diagram.getOrNull()?.setChoiceTargets(choices.mapTo(mutableSetOf()) { it.to })
        onDispose { }
    }
    DisposableEffect(diagram, explorer) {
        diagram.getOrNull()?.setStepActions(
            previous = {
                if (explorer.back()) {
                    choices = emptyList()
                    pathRevision++
                    persistPath(service, record, explorer)
                    service.select(record, explorer.currentNodeId, navigateSource = diagram.getOrNull()?.navigationPinned != true)
                }
            },
            next = {
                when (val result = explorer.next()) {
                    is StepResult.Choose -> choices = result.edges
                    is StepResult.Moved -> {
                        pathRevision++
                        choices = emptyList()
                        persistPath(service, record, explorer)
                        service.select(record, explorer.currentNodeId, navigateSource = diagram.getOrNull()?.navigationPinned != true)
                    }
                    StepResult.End, StepResult.Limit -> Unit
                    StepResult.InvalidChoice -> Unit
                }
            },
        )
        onDispose { diagram.getOrNull()?.setStepActions(null, null) }
    }
    DisposableEffect(diagram, current, pathRevision) {
        var active = true
        diagram.getOrNull()?.let { builder ->
            builder.setStepAvailability(explorer.path.isNotEmpty(), explorer.availableEdges().isNotEmpty())
            ApplicationManager.getApplication().invokeLater {
                if (active) builder.showUnderstandingPath(explorer.path, current, centerNode = pathRevision > 0)
            }
        }
        onDispose { active = false }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(record.question)
        diagram.onSuccess { builder ->
            key(builder) {
                SwingPanel(factory = { builder.jComponent }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }.onFailure { failure -> Text("图无法加载：${failure.message}") }
    }
}

private fun persistPath(service: UnderstandingService, record: UnderstandingRecord, explorer: StaticExplorer) {
    service.graphStates.update(GraphLayerKey(record.id)) { state ->
        val browse = state.browse ?: service.repository.getBrowseState(record.id) ?: BrowseState(record.id)
        state.copy(browse = browse.copy(staticPath = explorer.path, nodeId = explorer.currentNodeId))
    }
    if (!service.repository.readOnly) {
        val previous = service.repository.getBrowseState(record.id)
        service.repository.saveBrowseState((previous ?: BrowseState(record.id)).copy(staticPath = explorer.path))
    }
}

private fun persistView(service: UnderstandingService, record: UnderstandingRecord, builder: UnderstandingDiagram, explorer: StaticExplorer) {
    saveGraphViewState(service, GraphLayerKey(record.id), builder, explorer.path, explorer.currentNodeId)
    if (!service.repository.readOnly) {
        runCatching {
            val center = builder.center
            val previous = service.repository.getBrowseState(record.id)
            service.repository.saveBrowseState((previous ?: BrowseState(record.id)).copy(
                zoom = builder.zoom,
                panX = center.x,
                panY = center.y,
                hasPan = true,
            ))
        }
    }
}
