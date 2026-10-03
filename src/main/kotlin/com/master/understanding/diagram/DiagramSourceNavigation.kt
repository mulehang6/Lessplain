package com.master.understanding.diagram

import com.master.understanding.core.StaticExplorer
import com.master.understanding.ide.GraphLayerKey
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.BrowseState
import com.master.understanding.model.RouteNavigationState
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.ui.QuestionCanvas
import com.master.understanding.ui.SourceNode
import com.master.understanding.ui.isCurrent
import com.master.understanding.ui.projectCanvasNavigationToSource
import com.master.understanding.ui.projectCanvasPathToSource
import com.master.understanding.ui.projectSourceBrowseToCanvas

/** Shares route persistence, source feedback and combined-canvas ID projection across all graph hosts. */
internal fun bindDiagramSourceNavigation(
    service: UnderstandingService,
    key: GraphLayerKey,
    diagram: UnderstandingDiagram,
    explorer: StaticExplorer?,
    record: UnderstandingRecord,
    canvas: QuestionCanvas? = null,
): AutoCloseable {
    var closed = false
    fun saveViewport() {
        if (closed) return
        saveGraphViewState(service, key, diagram, explorer?.path.orEmpty(), explorer?.currentNodeId,
            explorer?.navigationState)
    }
    if (explorer == null) {
        diagram.onViewportChanged { saveViewport() }
        return AutoCloseable {
            if (!closed) {
                saveViewport()
                closed = true
            }
        }
    }

    fun sourceAt(nodeId: String): SourceNode? = canvas?.sources?.get(nodeId)
        ?: if (canvas == null) SourceNode(record, nodeId) else null

    fun isCurrentSource(nodeId: String): Boolean {
        val source = sourceAt(nodeId) ?: return false
        if (canvas != null && !canvas.isCurrent(service, record)) return false
        return runCatching { service.repository.getRecord(source.record.id).revision == source.record.revision }
            .getOrDefault(false)
    }

    fun persist(saveRepository: Boolean = true) {
        if (closed) return
        saveViewport()
        if (saveRepository && key.questionNodeId == null && !service.repository.readOnly) {
            val previous = service.repository.getBrowseState(record.id) ?: BrowseState(record.id)
            val center = diagram.center
            service.repository.saveBrowseState(
                previous.copy(
                    nodeId = explorer.currentNodeId,
                    staticPath = explorer.path,
                    navigationState = explorer.navigationState,
                    zoom = diagram.zoom,
                    panX = center.x,
                    panY = center.y,
                    hasPan = true,
                ),
            )
        }
    }

    val navigation = DiagramRouteNavigation(diagram, (canvas?.record ?: record).graph, explorer, onMoved = { route ->
        persist()
        val source = sourceAt(route.currentNodeId)
        if (source != null) {
            val path = if (canvas == null) route.path else projectCanvasPathToSource(canvas, route.path, source)
            val snapshot = if (canvas == null) route.navigationState
            else projectCanvasNavigationToSource(canvas, route.navigationState, source)
            service.select(
                source.record, source.nodeId, navigateSource = !diagram.navigationPinned,
                staticPath = path.orEmpty(), navigationState = snapshot ?: RouteNavigationState(source.nodeId),
            )
        }
    }, isCurrent = ::isCurrentSource)
    val sourceSession = diagram.bindSourceNavigation(service, navigation, ::sourceAt)
    diagram.onViewportChanged { persist(saveRepository = false) }
    val sourceSelection = service.onSelectionChanged { recordId, nodeId ->
        val id = if (canvas == null) nodeId?.takeIf { recordId == record.id }
        else canvas.sources.entries.firstOrNull { (_, source) ->
            source.record.id == recordId && source.nodeId == nodeId
        }?.key
        if (id != null) {
            val source = canvas?.sources?.get(id)
            val projected = if (canvas != null && source != null) {
                val browse = service.graphStates.get(GraphLayerKey(source.record.id)).browse
                    ?: service.repository.getBrowseState(source.record.id)
                browse?.let { state -> projectSourceBrowseToCanvas(canvas, state, source, id) }
            } else null
            val saved = projected ?: graphBrowseState(service, key)?.takeIf { it.nodeId == id }
            if (navigation.synchronize(id, saved)) persist()
        } else navigation.cancelChoice()
    }
    return AutoCloseable {
        if (!closed) {
            try {
                if (isCurrentSource(explorer.currentNodeId)) persist()
            } finally {
                closed = true
                sourceSelection.close()
                sourceSession.close()
                navigation.close()
            }
        }
    }
}

/** Maps source buttons to the currently explored canvas, including prefixed follow-up IDs. */
internal fun UnderstandingDiagram.bindSourceNavigation(
    service: UnderstandingService,
    navigation: DiagramRouteNavigation,
    sourceOf: (String) -> SourceNode?,
): AutoCloseable {
    val registration = service.graphStates.navigationSessions.register(
        matches = { recordId, nodeId ->
            val source = sourceOf(navigation.explorer.currentNodeId)
            !navigation.isClosed && isShowing && source?.record?.id == recordId && source.nodeId == nodeId &&
                runCatching { service.repository.getRecord(recordId).revision == source.record.revision }.getOrDefault(false)
        },
        busy = { navigation.hasChoice },
        previous = ::previousStep,
        next = ::nextStep,
    )
    navigation.onActivated = registration::activate
    return AutoCloseable { navigation.onActivated = {}; registration.close() }
}
