package com.lessplain.diagram

import java.awt.geom.Rectangle2D

/** 仅重绘受图动画影响的可见图形。 */
internal fun UnderstandingDiagram.advanceFlow() {
    flowPhase = (flowPhase - 1.5f + UnderstandingDiagram.DASH_CYCLE) % UnderstandingDiagram.DASH_CYCLE
    val highlighted = highlightedEdgeIds
    layout.edges.filter { it.edge.id in highlighted }.forEach { repaintGraphArea(it.paintBounds) }
}

internal fun UnderstandingDiagram.repaintGraphArea(bounds: Rectangle2D) {
    if (width <= 0 || height <= 0) return
    val x = width / 2.0 + (bounds.x - centerX) * zoom
    val y = height / 2.0 + (bounds.y - centerY) * zoom
    val w = bounds.width * zoom
    val h = bounds.height * zoom
    if (x - 18 > width || y - 18 > height || x + w + 18 < 0 || y + h + 18 < 0) return
    repaint((x - 18).toInt(), (y - 18).toInt(), (w + 36).toInt().coerceAtLeast(1),
        (h + 36).toInt().coerceAtLeast(1))
}

/** 动画计时器遵循可见性、用户设置和减少动态效果的偏好。 */
internal fun UnderstandingDiagram.updateTimer() {
    val reducedMotion = java.lang.Boolean.getBoolean("lessplain.reduceMotion")
    if (!disposed && !reducedMotion && animateFlow && isShowing && highlightedEdgeIds.isNotEmpty())
        flowTimer.start() else flowTimer.stop()
    if (!disposed && !reducedMotion && isShowing && choiceTargets.isNotEmpty())
        choiceTimer.start() else choiceTimer.stop()
    if (!isShowing) {
        copyTimer.stop()
        copiedNodeId = null
    }
    if (!isShowing && cameraTimer.isRunning) {
        cameraTimer.stop()
        centerX = cameraMotion.targetX
        centerY = cameraMotion.targetY
    }
}
