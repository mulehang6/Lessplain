package com.master.understanding.ui

import com.master.understanding.core.StaticExplorer

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
