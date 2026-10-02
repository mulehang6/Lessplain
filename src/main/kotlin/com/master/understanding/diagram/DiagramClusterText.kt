package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.geom.Rectangle2D
import javax.swing.UIManager
import kotlin.math.min

/** The title is measured before graph placement so long questions do not overlap nodes. */
internal data class ClusterTextLayout(
    val titleLines: List<String>,
    val titleLineHeight: Int,
    val height: Double,
)

internal object DiagramClusterText {
    private const val HORIZONTAL_PADDING = 18.0
    private const val TOP_PADDING = 16.0
    private const val BOTTOM_PADDING = 16.0
    private const val MAX_TEXT_WIDTH = 640

    private val baseFont: Font get() = UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)
    private val titleFont: Font get() = baseFont.deriveFont(Font.BOLD)

    fun measure(title: String, width: Double): ClusterTextLayout {
        val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            val availableWidth = min((width - HORIZONTAL_PADDING * 2 - 28).toInt(), MAX_TEXT_WIDTH)
                .coerceAtLeast(1)
            val titleMetrics = graphics.getFontMetrics(titleFont)
            val titleLines = wrap(title, availableWidth, titleMetrics::stringWidth)
            val height = TOP_PADDING + titleLines.size * titleMetrics.height + BOTTOM_PADDING
            return ClusterTextLayout(titleLines, titleMetrics.height, height)
        } finally {
            graphics.dispose()
        }
    }

    fun titleBounds(cluster: PlacedCluster): Rectangle2D.Double = Rectangle2D.Double(
        cluster.bounds.x + HORIZONTAL_PADDING,
        cluster.bounds.y + TOP_PADDING,
        cluster.bounds.width - HORIZONTAL_PADDING * 2,
        cluster.textLayout.titleLines.size * cluster.textLayout.titleLineHeight.toDouble(),
    )

    fun editBounds(cluster: PlacedCluster): Rectangle2D.Double {
        val title = titleBounds(cluster)
        return Rectangle2D.Double(title.maxX - 24, title.y - 3, 24.0, 24.0)
    }

    fun paint(graphics: Graphics2D, cluster: PlacedCluster, state: DiagramTitleState = DiagramTitleState()) {
        val bounds = titleBounds(cluster)
        if (cluster.id == state.hovered || cluster.id == state.selected || cluster.id == state.pressed) {
            val active = cluster.id == state.pressed || cluster.id == state.selected
            graphics.color = if (active) JBColor(java.awt.Color(0xDDE9FA), java.awt.Color(0x30435B))
                else JBColor(java.awt.Color(0xEAF0F7), java.awt.Color(0x303640))
            graphics.fillRoundRect((bounds.x - 5).toInt(), (bounds.y - 5).toInt(),
                (bounds.width + 10).toInt(), (bounds.height + 10).toInt(), 8, 8)
        }
        val text = cluster.textLayout
        var baseline = cluster.bounds.y + TOP_PADDING
        graphics.font = titleFont
        graphics.color = JBColor.foreground()
        val titleAscent = graphics.fontMetrics.ascent
        text.titleLines.forEach { line ->
            val x = cluster.bounds.centerX - 14 - graphics.fontMetrics.stringWidth(line) / 2.0
            graphics.drawString(line, x.toFloat(), (baseline + titleAscent).toFloat())
            baseline += text.titleLineHeight
        }
        if (cluster.id == state.hovered) {
            val edit = editBounds(cluster)
            val x = edit.x.toInt() + 6
            val y = edit.y.toInt() + 6
            graphics.stroke = java.awt.BasicStroke(1.5f)
            graphics.drawLine(x, y + 11, x + 10, y + 1)
            graphics.drawLine(x + 3, y + 13, x + 13, y + 3)
            graphics.drawLine(x + 10, y + 1, x + 13, y + 3)
            graphics.drawLine(x, y + 11, x, y + 14)
            graphics.drawLine(x, y + 14, x + 3, y + 13)
        }
    }

    private fun wrap(value: String, width: Int, measure: (String) -> Int): List<String> {
        val lines = mutableListOf<String>()
        value.split('\n').forEach { paragraph ->
            val current = StringBuilder()
            paragraph.codePoints().forEach { point ->
                val char = String(Character.toChars(point))
                if (current.isNotEmpty() && measure(current.toString() + char) > width) {
                    lines += current.toString()
                    current.clear()
                }
                current.append(char)
            }
            lines += current.toString()
        }
        return lines.ifEmpty { listOf("") }
    }
}
