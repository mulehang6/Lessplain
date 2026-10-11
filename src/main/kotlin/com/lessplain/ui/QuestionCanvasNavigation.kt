package com.lessplain.ui

import com.lessplain.ide.UnderstandingService
import com.lessplain.model.UnderstandingRecord
import com.lessplain.core.StaticExplorer
import com.lessplain.model.BrowseState
import com.lessplain.model.RouteNavigationState

/** 带前缀的组合画布，仅在全部参与记录的版本未变化时有效。 */
internal fun QuestionCanvas.isCurrent(service: UnderstandingService, owner: UnderstandingRecord): Boolean =
    (sources.values.map { it.record } + owner).distinctBy { it.id }.all { snapshot ->
        runCatching { service.repository.getRecord(snapshot.id).revision == snapshot.revision }.getOrDefault(false)
    }


/** 仅当所有边属于选中的来源记录时，才投影组合画布路线。 */
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

/** 将有效来源记录路线映射回组合画布的边 ID。 */
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

/** 待确定前缀保留在来源记录命名空间内，不依赖高亮边的并集。 */
internal fun projectCanvasNavigationToSource(
    canvas: QuestionCanvas,
    navigation: RouteNavigationState,
    source: SourceNode,
): RouteNavigationState? {
    val prefix = canvas.sources[navigation.prefixNodeId]?.takeIf { it.record.id == source.record.id } ?: return null
    val path = projectCanvasPathToSource(canvas, navigation.selectedEdgeIds, source) ?: return null
    return RouteNavigationState(prefix.nodeId, path)
}

/** 将已保存来源路线映射到画布，源码反馈时保留待确定的上游前缀。 */
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
