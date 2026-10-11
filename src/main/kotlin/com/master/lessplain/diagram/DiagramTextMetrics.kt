package com.master.lessplain.diagram

import java.awt.Font
import java.awt.image.BufferedImage
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.UIManager

/** 测量图中文字，不依赖活动画布的图形上下文。 */
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
                        val breaks = BreakIterator.getLineInstance(Locale.ROOT).apply { setText(paragraph) }
                        var start = breaks.first()
                        var end = breaks.next()
                        var line = ""
                        while (end != BreakIterator.DONE) {
                            val token = paragraph.substring(start, end)
                            if (line.isNotEmpty() && metrics.stringWidth(line + token) > width) {
                                add(line.trimEnd())
                                line = ""
                            }
                            if (metrics.stringWidth(token.trimStart()) <= width) {
                                line += if (line.isEmpty()) token.trimStart() else token
                            } else {
                                // 单个标识符超过节点宽度时才按码点拆分，不拆开普通英文词。
                                token.codePoints().forEach { point ->
                                    val character = String(Character.toChars(point))
                                    if (line.isNotEmpty() && metrics.stringWidth(line + character) > width) {
                                        add(line.trimEnd())
                                        line = character
                                    } else line += character
                                }
                            }
                            start = end
                            end = breaks.next()
                        }
                        add(line.trimEnd())
                    }
                }
            } finally { graphics.dispose() }
        }

    fun nodeSize(label: String, question: Boolean, branch: Boolean = false, font: Font = font()): Pair<Double, Double> {
        val graphics = image.get().createGraphics()
        try {
            graphics.font = font
            val metrics = graphics.fontMetrics
            val longest = label.split('\n').maxOfOrNull(metrics::stringWidth) ?: 0
            val inset = DiagramNodeSemantics.textInset(font, branch)
            val width = if (question) 330 else (longest + 28 + inset).coerceIn(220, 360)
            val count = lines(label, width - 24 - inset, font).size.coerceAtLeast(1)
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
