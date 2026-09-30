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
            val availableWidth = min((width - HORIZONTAL_PADDING * 2).toInt(), MAX_TEXT_WIDTH)
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

    fun paint(graphics: Graphics2D, cluster: PlacedCluster) {
        val text = cluster.textLayout
        var baseline = cluster.bounds.y + TOP_PADDING
        graphics.font = titleFont
        graphics.color = JBColor.foreground()
        val titleAscent = graphics.fontMetrics.ascent
        text.titleLines.forEach { line ->
            val x = cluster.bounds.centerX - graphics.fontMetrics.stringWidth(line) / 2.0
            graphics.drawString(line, x.toFloat(), (baseline + titleAscent).toFloat())
            baseline += text.titleLineHeight
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
