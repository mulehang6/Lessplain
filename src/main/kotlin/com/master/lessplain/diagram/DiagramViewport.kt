package com.master.lessplain.diagram

import com.master.lessplain.model.BrowseState
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

internal fun UnderstandingDiagram.restoreViewport(saved: BrowseState, persist: Boolean = true) {
    if (!saved.hasPan || !saved.panX.isFinite() || !saved.panY.isFinite()) return
    cameraTimer.stop()
    pendingCenterNodeId = null
    zoom = saved.zoom
    centerX = saved.panX
    centerY = saved.panY
    if (!persist) cancelViewportSave()
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
    val members = clusterSpecs.firstOrNull { it.id == id }?.nodeIds.orEmpty()
    val nodeId = overlays.clusterTargets[id]?.takeIf { it in members }
        ?: currentNodeId?.takeIf { it in members } ?: members.firstOrNull()
    val target = nodeId?.let { layout.nodes[it]?.bounds } ?: cluster.bounds
    val x = target.centerX
    val y = target.centerY
    if (animate) moveCameraTo(x, y) else {
        centerX = x
        centerY = y
        repaint()
        queueViewportSave()
    }
    selectedNodeId = nodeId
    currentNodeId = selectedNodeId
    if (nodeId != null) overlays.clusterTargets[id] = nodeId
    if (animate) selectedNodeId?.let { node -> clusterCallbacks.toList().forEach { it(node) } }
    repaint()
}

internal fun UnderstandingDiagram.requestFocusCluster(id: String) {
    pendingFocusClusterId = id
    applyPendingClusterFocus()
}

/** A requested group survives resize events until asynchronous layout is available. */
internal fun UnderstandingDiagram.applyPendingClusterFocus() {
    if (!layoutReady || width <= 0 || height <= 0) return
    pendingFocusClusterId?.let { id ->
        pendingFocusClusterId = null
        focusCluster(id, animate = false)
    }
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
