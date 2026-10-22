package com.lessplain.diagram

import com.lessplain.core.StaticExplorer
import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.BrowseState
import com.lessplain.model.RouteNavigationState
import com.lessplain.model.UnderstandingRecord
import com.lessplain.ui.QuestionCanvas
import com.lessplain.ui.SourceNode
import com.lessplain.ui.isCurrent
import com.lessplain.ui.projectCanvasNavigationToSource
import com.lessplain.ui.projectCanvasPathToSource
import com.lessplain.ui.projectSourceBrowseToCanvas
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

/** 所有图宿主共用路径持久化、源码反馈和组合画布 ID 映射。 */
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
        saveGraphViewState(service.graphStates, key, diagram, explorer?.path.orEmpty(), explorer?.currentNodeId,
            explorer?.navigationState)
    }
    fun sourceAt(nodeId: String): SourceNode? = canvas?.sources?.get(nodeId)
        ?: if (canvas == null) SourceNode(record, nodeId) else null

    val sourceFeedback = diagram.bindSourceLocationFeedback(service, ::sourceAt)
    if (explorer == null) {
        diagram.onViewportChanged { saveViewport() }
        return AutoCloseable {
            if (!closed) {
                saveViewport()
                closed = true
                sourceFeedback.close()
            }
        }
    }

    fun isCurrentSource(nodeId: String): Boolean {
        val source = sourceAt(nodeId) ?: return false
        if (canvas != null && !canvas.isCurrent(service, record)) return false
        return service.isCurrent(source.record)
    }

    fun persist(saveRepository: Boolean = true) {
        if (closed) return
        saveViewport()
        if (saveRepository && key.questionNodeId == null && !service.readOnly) {
            val previous = service.storage.browse(record.id) ?: BrowseState(record.id)
            val center = diagram.center
            service.storage.saveBrowse(
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

    val navigation = DiagramRouteNavigation(diagram, explorer, onMoved = { route ->
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
                    ?: service.storage.browse(source.record.id)
                browse?.let { state -> projectSourceBrowseToCanvas(canvas, state, source, id) }
            } else null
            val saved = projected ?: graphBrowseState(service, key)?.takeIf { it.nodeId == id }
            if (navigation.synchronize(id, saved)) persist()
        } else navigation.cancelChoice()
    }
    return AutoCloseable {
        if (!closed) {
            try {
                // 过期的是源码导航资格，视角仍属于这个稳定画布，必须在重建前保存。
                persist(saveRepository = isCurrentSource(explorer.currentNodeId))
            } finally {
                closed = true
                sourceSelection.close()
                sourceFeedback.close()
                sourceSession.close()
                navigation.close()
            }
        }
    }
}

/** 将源码按钮映射到当前探索的画布，包括带前缀的追问 ID。 */
internal fun UnderstandingDiagram.bindSourceNavigation(
    service: UnderstandingService,
    navigation: DiagramRouteNavigation,
    sourceOf: (String) -> SourceNode?,
): AutoCloseable {
    val registration = service.graphStates.navigationSessions.register(
        matches = { recordId, nodeId ->
            val source = sourceOf(navigation.explorer.currentNodeId)
            !navigation.isClosed && isShowing && source?.record?.id == recordId && source.nodeId == nodeId &&
                service.isCurrent(source.record)
        },
        busy = { navigation.hasChoice },
        previous = ::previousStep,
        next = ::nextStep,
        sourceStep = navigation::stepFromSource,
        cancel = navigation::cancel,
    )
    val visibility = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
            registration.activate()
        }
    }
    addHierarchyListener(visibility)
    navigation.onActivated = registration::activate
    return AutoCloseable {
        removeHierarchyListener(visibility)
        navigation.onActivated = {}
        registration.close()
    }
}
