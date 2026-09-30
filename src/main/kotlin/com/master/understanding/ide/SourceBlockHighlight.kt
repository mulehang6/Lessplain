package com.master.understanding.ide

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/** One rectangular code area, shared by the hint and the hover highlight. */
internal fun sourceBlockBounds(editor: Editor, startOffset: Int, endOffset: Int): Rectangle? {
    val document = editor.document
    val start = startOffset.coerceIn(0, document.textLength)
    val end = endOffset.coerceIn(start, document.textLength)
    if (start == end) return null

    val first = document.getLineNumber(start)
    val last = document.getLineNumber(end - 1)
    val chars = document.charsSequence
    var left = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    for (line in first..last) {
        var from = maxOf(start, document.getLineStartOffset(line))
        var to = minOf(end, document.getLineEndOffset(line))
        while (from < to && chars[from].isWhitespace()) from++
        while (to > from && chars[to - 1].isWhitespace()) to--
        if (to <= from) continue
        left = minOf(left, editor.offsetToXY(from).x)
        right = maxOf(right, editor.offsetToXY(to).x)
    }
    if (left == Int.MAX_VALUE) return null
    val top = editor.offsetToXY(document.getLineStartOffset(first)).y
    val bottom = editor.offsetToXY(document.getLineStartOffset(last)).y + editor.lineHeight
    val padding = 5
    return Rectangle(left - padding, top - 2, (right - left + padding * 2).coerceAtLeast(1), bottom - top + 4)
}

/** A translucent left-to-right reveal over the resolved evidence, beneath editor text. */
internal class SourceSweepRenderer(private val progress: () -> Float) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val amount = progress()
        if (amount <= 0f) return
        val g = graphics.create() as Graphics2D
        try {
            val bounds = sourceBlockBounds(editor, highlighter.startOffset, highlighter.endOffset) ?: return
            val revealed = (bounds.width * amount).toInt().coerceAtLeast(1)
            g.clipRect(bounds.x, bounds.y, revealed, bounds.height)
            g.paint = GradientPaint(bounds.x.toFloat(), bounds.y.toFloat(), Color(128, 86, 216, 105),
                (bounds.x + bounds.width).toFloat(), bounds.y.toFloat(), Color(128, 86, 216, 28))
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8)
        } finally { g.dispose() }
    }
}
