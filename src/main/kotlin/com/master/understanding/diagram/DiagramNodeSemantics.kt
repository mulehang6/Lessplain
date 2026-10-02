package com.master.understanding.diagram

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.math.ceil

/** Semantic colors and the graph-fork glyph share no navigation or hit-test state. */
internal object DiagramNodeSemantics {
    val successFill = JBColor(Color(0xDBF3DE), Color(0x244B38))
    val successBorder = JBColor(Color(0x238344), Color(0x72C58F))
    val failureFill = JBColor(Color(0xFFDAD6), Color(0x682E33))
    val failureBorder = JBColor(Color(0xC62828), Color(0xFF7979))
    val unclassifiedFill = JBColor(Color(0xE7EBF1), Color(0x37424B))
    val unclassifiedBorder = JBColor(Color(0x64748B), Color(0x9AA4B2))
    val branchColor = JBColor(Color(0xA65300), Color(0xF2B35E))

    fun endFill(outcome: String?): Color = when (outcome) {
        "success" -> successFill
        "failure" -> failureFill
        else -> unclassifiedFill
    }

    fun endBorder(outcome: String?): Color = when (outcome) {
        "success" -> successBorder
        "failure" -> failureBorder
        else -> unclassifiedBorder
    }

    fun iconSize(font: Font): Double = font.size2D.toDouble()
    fun textInset(font: Font, branch: Boolean): Int = if (branch) ceil(iconSize(font) * 1.5).toInt() else 0

    fun iconBounds(bounds: Rectangle2D, font: Font, ascent: Int, descent: Int): Rectangle2D.Double {
        val size = iconSize(font)
        return Rectangle2D.Double(bounds.x + 12, bounds.y + 14 + (ascent + descent - size) / 2, size, size)
    }

    /** Fixed left-to-right fork; drawing dimensions follow the label font before the camera transform. */
    fun paintBranch(g: Graphics2D, bounds: Rectangle2D) {
        val icon = g.create() as Graphics2D
        try {
            icon.translate(bounds.x, bounds.y)
            icon.scale(bounds.width, bounds.height)
            icon.color = branchColor
            icon.stroke = BasicStroke(0.095f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val path = Path2D.Double().apply {
                moveTo(0.10, 0.5); lineTo(0.38, 0.5)
                lineTo(0.65, 0.20); lineTo(0.90, 0.20)
                moveTo(0.38, 0.5); lineTo(0.65, 0.80); lineTo(0.90, 0.80)
                moveTo(0.76, 0.07); lineTo(0.90, 0.20); lineTo(0.76, 0.33)
                moveTo(0.76, 0.67); lineTo(0.90, 0.80); lineTo(0.76, 0.93)
            }
            icon.draw(path)
        } finally { icon.dispose() }
    }
}
