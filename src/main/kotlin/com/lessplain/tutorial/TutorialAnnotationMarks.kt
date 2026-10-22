package com.lessplain.tutorial

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/** 编辑器只负责批注笔迹，入口窗口由两个表面共享的选区控制器管理。 */
internal class TutorialAnnotationMarks(private val editor: Editor, private val service: TutorialService, private val id: String) : AutoCloseable {
    private val highlighters = mutableListOf<RangeHighlighter>()
    fun refresh() {
        clear()
        val annotations = service.store.annotations(id).filter { !it.orphaned && it.end > it.start && it.end <= editor.document.textLength }
        annotationInkRuns(annotations).forEach { run ->
            highlighters += editor.markupModel.addRangeHighlighter(run.start, run.end, HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE).also { highlighter ->
                highlighter.customRenderer = object : CustomHighlighterRenderer {
                    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
                        if (!highlighter.isValid) return
                        val first = editor.document.getLineNumber(highlighter.startOffset)
                        val last = editor.document.getLineNumber(highlighter.endOffset)
                        val g = graphics.create() as Graphics2D
                        try {
                            for (line in first..last) {
                                val a = maxOf(highlighter.startOffset, editor.document.getLineStartOffset(line))
                                val b = minOf(highlighter.endOffset, editor.document.getLineEndOffset(line))
                                if (b <= a) continue
                                val height = editor.lineHeight
                                var from = editor.offsetToXY(a)
                                var previous = from
                                for (offset in (a + 1)..b) {
                                    val point = editor.offsetToXY(offset)
                                    if (point.y != from.y || offset == b) {
                                        val right = if (point.y == from.y) point.x else previous.x + editor.colorsScheme.editorFontSize
                                        paintInk(g, Rectangle(from.x, from.y, (right - from.x).coerceAtLeast(3), height), run.kind, run.seed)
                                        from = point
                                    }
                                    previous = point
                                }
                            }
                        } finally { g.dispose() }
                    }
                }
            }
        }
        editor.contentComponent.repaint()
    }
    private fun clear() { highlighters.forEach { editor.markupModel.removeHighlighter(it) }; highlighters.clear() }
    override fun close() = clear()
}
