package com.master.understanding.diagram

import com.master.understanding.model.BrowseState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Camera positioning and viewport restoration for a graph canvas. */
internal fun UnderstandingDiagram.fitContent() {
    if (width <= 0 || height <= 0) return
    cameraTimer.stop()
    val bounds = layout.bounds
    zoom = min(width.toDouble() / max(bounds.width, 1.0),
        height.toDouble() / max(bounds.height, 1.0))
        .coerceIn(UnderstandingDiagram.MIN_ZOOM, UnderstandingDiagram.MAX_ZOOM)
    centerX = bounds.centerX
    centerY = bounds.centerY
    repaint()
    queueViewportSave()
}

internal fun UnderstandingDiagram.initializeViewport() {
    if (!layoutReady) {
        requestLayout()
        return
    }
    initializedViewport = true
    val saved = directionViewports[activeDirection] ?:
        if (activeDirection == LayoutDirection.VERTICAL) initialState else null
    if (saved != null && saved.hasPan && saved.panX.isFinite() && saved.panY.isFinite()) {
        zoom = saved.zoom
        centerX = saved.panX
        centerY = saved.panY
    } else {
        val focus = currentNodeId?.let(layout.nodes::get)?.bounds ?: layout.nodes.values.firstOrNull()?.bounds
            ?: layout.clusters.firstOrNull()?.bounds
        if (focus == null) fitContent() else {
            zoom = min(1.0, min(width / (focus.width + 180), height / (focus.height + 180)))
            centerX = focus.centerX
            centerY = focus.centerY
        }
    }
    repaint()
}

internal fun UnderstandingDiagram.restoreViewport(saved: BrowseState) {
    if (!saved.hasPan || !saved.panX.isFinite() || !saved.panY.isFinite()) return
    zoom = saved.zoom
    centerX = saved.panX
    centerY = saved.panY
    repaint()
}

internal fun UnderstandingDiagram.centerOnNode(nodeId: String) {
    val bounds = layout.nodes[nodeId]?.bounds ?: return
    if (width <= 0 || height <= 0) {
        pendingCenterNodeId = nodeId
        return
    }
    pendingCenterNodeId = null
    moveCameraTo(bounds.centerX, bounds.centerY)
}

internal fun UnderstandingDiagram.focusCluster(id: String, animate: Boolean = true) {
    val cluster = layout.clusters.firstOrNull { it.id == id } ?: return
    if (width <= 0 || height <= 0) return
    val targetZoom = min((width - 24).toDouble() / cluster.bounds.width,
        (height - 24).toDouble() / cluster.bounds.height)
        .coerceIn(UnderstandingDiagram.MIN_ZOOM, UnderstandingDiagram.MAX_ZOOM)
    zoom = targetZoom
    val x = cluster.bounds.centerX
    val y = cluster.bounds.centerY
    if (animate) moveCameraTo(x, y) else {
        centerX = x
        centerY = y
        repaint()
        queueViewportSave()
    }
    selectedNodeId = clusterSpecs.firstOrNull { it.id == id }?.nodeIds?.firstOrNull()
    currentNodeId = selectedNodeId
    if (animate) selectedNodeId?.let { node -> clusterCallbacks.toList().forEach { it(node) } }
    repaint()
}

internal fun UnderstandingDiagram.requestFocusCluster(id: String) {
    if (width > 0 && height > 0) focusCluster(id, animate = false)
    else pendingFocusClusterId = id
}

internal fun UnderstandingDiagram.moveCameraTo(x: Double, y: Double) {
    if (cameraTimer.isRunning && abs(cameraMotion.targetX - x) < 0.5 &&
        abs(cameraMotion.targetY - y) < 0.5) return
    cameraTimer.stop()
    cameraMotion.retarget(centerX, centerY, x, y, System.nanoTime())
    if (!isShowing || !hasPainted || java.lang.Boolean.getBoolean("master.understanding.reduceMotion") ||
        (abs(centerX - x) < 0.5 && abs(centerY - y) < 0.5)) {
        centerX = x
        centerY = y
        repaint()
        queueViewportSave()
        return
    }
    cameraTimer.start()
}

internal fun UnderstandingDiagram.advanceCamera() {
    val now = System.nanoTime()
    val position = cameraMotion.positionAt(now)
    centerX = position.x
    centerY = position.y
    if (cameraMotion.isFinished(now)) {
        cameraTimer.stop()
        queueViewportSave()
    }
    repaint()
}
