package com.master.understanding.ui

import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.min

/** Shared control states for Swing graph buttons and editor-painted navigation buttons. */
internal object UnderstandingControlPainter {
    fun paintBackground(
        g: Graphics2D,
        bounds: Rectangle2D,
        enabled: Boolean = true,
        hovered: Boolean = false,
        pressed: Boolean = false,
        focused: Boolean = false,
        quiet: Boolean = false,
    ) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val active = enabled && (hovered || pressed)
        if (quiet && !active && !focused) return
        val shape = RoundRectangle2D.Double(bounds.x + 0.5, bounds.y + 0.5,
            (bounds.width - 1).coerceAtLeast(0.0), (bounds.height - 1).coerceAtLeast(0.0),
            UnderstandingUiStyle.CONTROL_ARC.toDouble(), UnderstandingUiStyle.CONTROL_ARC.toDouble())
        g.color = when {
            enabled && pressed -> UnderstandingUiStyle.controlPressed
            enabled && hovered -> UnderstandingUiStyle.controlHover
            else -> UnderstandingUiStyle.surface
        }
        g.fill(shape)
        g.color = if (focused && enabled) UnderstandingUiStyle.accent else UnderstandingUiStyle.border
        g.stroke = BasicStroke(if (focused && enabled) 1.5f else 1f)
        g.draw(shape)
    }

    /** Chevron dimensions follow the existing hit region, independent of editor font glyphs. */
    fun paintNavigation(g: Graphics2D, bounds: Rectangle2D, next: Boolean, enabled: Boolean, hovered: Boolean) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.clip(bounds)
            paintBackground(graphics, bounds, enabled, hovered, quiet = true)
            graphics.color = if (enabled) UnderstandingUiStyle.controlForeground else UnderstandingUiStyle.disabledForeground
            val size = min(bounds.width, bounds.height).times(0.42).coerceAtMost(8.0)
            val direction = if (next) 1 else -1
            val x = bounds.centerX
            val y = bounds.centerY
            graphics.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.draw(Path2D.Double().apply {
                moveTo(x - direction * size / 4, y - size / 2)
                lineTo(x + direction * size / 4, y)
                lineTo(x - direction * size / 4, y + size / 2)
            })
        } finally {
            graphics.dispose()
        }
    }
}
