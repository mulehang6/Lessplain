package com.master.lessplain.diagram

import java.awt.geom.Point2D

/** The graph camera moves between nodes and can retarget from its current position. */
internal class UnderstandingCameraMotion(initialX: Double, initialY: Double) {
    private var startX = initialX
    private var startY = initialY
    var targetX = initialX
        private set
    var targetY = initialY
        private set
    private var startedAt = 0L

    fun retarget(fromX: Double, fromY: Double, toX: Double, toY: Double, now: Long) {
        startX = fromX
        startY = fromY
        targetX = toX
        targetY = toY
        startedAt = now
    }

    fun positionAt(now: Long): Point2D.Double {
        val progress = ((now - startedAt).toDouble() / DURATION_NANOS).coerceIn(0.0, 1.0)
        val eased = easeInOut(progress)
        return Point2D.Double(
            startX + (targetX - startX) * eased,
            startY + (targetY - startY) * eased,
        )
    }

    fun isFinished(now: Long): Boolean = now - startedAt >= DURATION_NANOS

    private fun easeInOut(progress: Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(12) {
            val t = (low + high) / 2
            val remaining = 1 - t
            val x = 3 * remaining * remaining * t * 0.77 + 3 * remaining * t * t * 0.175 + t * t * t
            if (x < progress) low = t else high = t
        }
        val t = (low + high) / 2
        val remaining = 1 - t
        return 3 * remaining * t * t + t * t * t
    }

    companion object {
        const val DURATION_NANOS = 240_000_000L
    }
}
