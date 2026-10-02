package com.master.understanding.diagram

import java.awt.Font
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.UIManager

/** Measures graph text without depending on a live canvas graphics context. */
internal object DiagramTextMetrics {
    private data class Key(val text: String, val font: Font, val width: Int)
    private val lineCache = ConcurrentHashMap<Key, List<String>>()
    private val image = ThreadLocal.withInitial { BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) }

    fun font(): Font = UIManager.getFont("Label.font") ?: Font(Font.DIALOG, Font.PLAIN, 12)

    fun lines(text: String, width: Int, font: Font = font()): List<String> =
        lineCache.computeIfAbsent(Key(text, font, width)) {
            val graphics = image.get().createGraphics()
            try {
                graphics.font = font
                val metrics = graphics.fontMetrics
                buildList {
                    text.split('\n').forEach { paragraph ->
                        var line = ""
                        paragraph.codePoints().forEach { point ->
                            val character = String(Character.toChars(point))
                            if (line.isNotEmpty() && metrics.stringWidth(line + character) > width) {
                                add(line)
                                line = character
                            } else line += character
                        }
                        add(line)
                    }
                }
            } finally { graphics.dispose() }
        }

    fun nodeSize(label: String, question: Boolean, branch: Boolean = false): Pair<Double, Double> {
        val font = font()
        val graphics = image.get().createGraphics()
        try {
            graphics.font = font
            val metrics = graphics.fontMetrics
            val longest = label.split('\n').maxOfOrNull(metrics::stringWidth) ?: 0
            val inset = DiagramNodeSemantics.textInset(font, branch)
            val width = if (question) 330 else (longest + 28 + inset).coerceIn(180, 260)
            val count = lines(label, width - 24 - inset, font).size.coerceIn(1, 3)
            return width.toDouble() to (count * metrics.height + 28).coerceAtLeast(56).toDouble()
        } finally { graphics.dispose() }
    }

    fun edgeLabelSize(label: String): Pair<Double, Double> {
        val graphics = image.get().createGraphics()
        try {
            graphics.font = font()
            return (graphics.fontMetrics.stringWidth(label.take(32)) + 12).toDouble() to
                (graphics.fontMetrics.height + 4).toDouble()
        } finally { graphics.dispose() }
    }
}
