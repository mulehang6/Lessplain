package com.master.lessplain.ui

import com.master.lessplain.ide.UnderstandingService
import com.master.lessplain.model.UnderstandingRecord
import com.master.lessplain.core.StaticExplorer
import com.master.lessplain.model.BrowseState
import com.master.lessplain.model.RouteNavigationState

/** A prefixed canvas remains valid only while every contributing record keeps its revision. */
internal fun QuestionCanvas.isCurrent(service: UnderstandingService, owner: UnderstandingRecord): Boolean =
    (sources.values.map { it.record } + owner).distinctBy { it.id }.all { snapshot ->
        runCatching { service.repository.getRecord(snapshot.id).revision == snapshot.revision }.getOrDefault(false)
    }


/** Projects a combined-canvas route only when every edge belongs to the selected source record. */
internal fun projectCanvasPathToSource(
    canvas: QuestionCanvas,
    canvasPath: List<String>,
    source: SourceNode,
): List<String>? {
    if (canvasPath.isEmpty()) return emptyList()
    val sourcePath = canvasPath.map { canvasEdgeId ->
        canvas.edgeSources[canvasEdgeId]
            ?.takeIf { it.record.id == source.record.id }
            ?.edgeId ?: return null
    }
    val restored = StaticExplorer(source.record.graph, sourcePath, source.nodeId)
    return sourcePath.takeIf { restored.path == sourcePath }
}

/** Maps a valid source-record route back to its combined-canvas edge IDs. */
internal fun projectSourcePathToCanvas(
    canvas: QuestionCanvas,
    sourcePath: List<String>,
    source: SourceNode,
    canvasNodeId: String,
): List<String>? {
    val localSource = canvas.sources[canvasNodeId]
    if (localSource == null || localSource.record.id != source.record.id || localSource.nodeId != source.nodeId) return null
    if (sourcePath.isEmpty()) return emptyList()
    val canvasPath = sourcePath.map { edgeId ->
        canvas.edgeSources.entries.singleOrNull { (_, mapped) ->
            mapped.record.id == source.record.id && mapped.edgeId == edgeId
        }?.key ?: return null
    }
    val restored = StaticExplorer(canvas.record.graph, canvasPath, canvasNodeId)
    return canvasPath.takeIf { restored.path == canvasPath }
}

/** Keeps the unresolved prefix in its source record's namespace, independently of highlighted edge unions. */
internal fun projectCanvasNavigationToSource(
    canvas: QuestionCanvas,
    navigation: RouteNavigationState,
    source: SourceNode,
): RouteNavigationState? {
    val prefix = canvas.sources[navigation.prefixNodeId]?.takeIf { it.record.id == source.record.id } ?: return null
    val path = projectCanvasPathToSource(canvas, navigation.selectedEdgeIds, source) ?: return null
    return RouteNavigationState(prefix.nodeId, path)
}

/** Maps a saved source route into the canvas, preserving the unresolved upstream prefix on source feedback. */
internal fun projectSourceBrowseToCanvas(
    canvas: QuestionCanvas,
    browse: BrowseState,
    source: SourceNode,
    canvasNodeId: String,
): BrowseState? {
    if (browse.recordId != source.record.id || browse.nodeId != source.nodeId) return null
    val path = projectSourcePathToCanvas(canvas, browse.staticPath, source, canvasNodeId) ?: return null
    val navigation = browse.navigationState?.let { saved ->
        val prefix = canvas.sources.entries.singleOrNull { (_, node) ->
            node.record.id == source.record.id && node.nodeId == saved.prefixNodeId
        }?.key ?: return null
        val restored = StaticExplorer(source.record.graph, browse.staticPath, source.nodeId, saved)
        if (restored.navigationState != saved) return null
        RouteNavigationState(prefix, path)
    }
    return browse.copy(recordId = canvas.record.id, nodeId = canvasNodeId, staticPath = path, navigationState = navigation)
}
