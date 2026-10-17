package com.lessplain.ide

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.lessplain.ui.UnderstandingControlPainter
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Rectangle2D

internal enum class SourceInlayButton { PREVIOUS, NEXT, EXPAND }

internal data class SourceInlayControlBounds(
    val hint: Rectangle,
    val previous: Rectangle,
    val next: Rectangle,
)

internal fun sourceInlayControlBounds(left: Int, top: Int, height: Int, textWidth: Int): SourceInlayControlBounds {
    val buttonWidth = 20
    val gap = 3
    val firstX = left + textWidth + 20
    return SourceInlayControlBounds(
        hint = Rectangle(left, top, textWidth + 20 + buttonWidth * 2 + gap, height),
        previous = Rectangle(firstX, top + 2, buttonWidth, (height - 4).coerceIn(1, 20)),
        next = Rectangle(firstX + buttonWidth + gap, top + 2, buttonWidth, (height - 4).coerceIn(1, 20)),
    )
}

/** 测量和绘制源码说明及其导航控件。 */
internal class InsightInlayRenderer(
    private val label: String,
    private val start: Int,
    private val end: Int,
    var canPrevious: Boolean,
    var canNext: Boolean,
) : EditorCustomElementRenderer {
    var hovered = false
    var hoveredButton: SourceInlayButton? = null
    var expanded = false

    private var sourceRange: (() -> Pair<Int, Int>?)? = null

    /** 几何直接读取 range marker，避免文档变化到异步监听器之间使用旧偏移。 */
    fun bindSourceRange(provider: () -> Pair<Int, Int>?) { sourceRange = provider }

    private data class LabelLayout(val lines: List<String>, val truncated: Boolean,
        val lineHeight: Int, val textWidth: Int, val actionLine: Boolean)

    private val displayLabel = sourceInlayDisplayLabel(label)

    private fun labelLayout(inlay: Inlay<*>): LabelLayout {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val metrics = editor.contentComponent.getFontMetrics(font)
        val available = (editor.scrollingModel.visibleArea.width * 0.62).toInt().coerceIn(160, 620)
        val all = wrapSourceInlayLabel(displayLabel, (available - LABEL_TEXT_INSET).coerceAtLeast(1), metrics::stringWidth)
        val truncated = !expanded && all.size > 2
        val lines = if (truncated) all.take(2).toMutableList().apply {
            var last = this[1]
            while (last.isNotEmpty() && metrics.stringWidth("$last…") > available) last = last.dropLast(1)
            this[1] = "$last…"
        } else all
        return LabelLayout(
            lines, truncated, metrics.height,
            lines.maxOf(metrics::stringWidth) + LABEL_TEXT_INSET,
            expanded && all.size > 2,
        )
    }

    private fun textWidth(inlay: Inlay<*>): Int = labelLayout(inlay).textWidth

    private fun left(inlay: Inlay<*>): Int {
        val range = sourceRange?.invoke() ?: (start to end)
        return sourceBlockBounds(inlay.editor, range.first, range.second)?.x ?: inlay.bounds?.x ?: 0
    }

    private fun controls(inlay: Inlay<*>): SourceInlayControlBounds {
        val bounds = inlay.bounds
        return sourceInlayControlBounds(left(inlay), bounds?.y ?: 0,
            bounds?.height ?: calcHeightInPixels(inlay), textWidth(inlay))
    }

    private fun buttonBounds(inlay: Inlay<*>, button: SourceInlayButton): Rectangle =
        controls(inlay).let { if (button == SourceInlayButton.PREVIOUS) it.previous else it.next }

    private fun expandBounds(inlay: Inlay<*>): Rectangle? {
        val layout = labelLayout(inlay)
        if (!layout.truncated && !layout.actionLine) return null
        val top = inlay.bounds?.y ?: 0
        val actionY = if (layout.truncated) top + layout.lineHeight else top + layout.lines.size * layout.lineHeight
        val metrics = inlay.editor.contentComponent.getFontMetrics(
            inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val x = if (layout.truncated) left(inlay) + 8 + LABEL_TEXT_INSET + metrics.stringWidth(layout.lines.last().dropLast(1))
            else left(inlay) + 8 + LABEL_TEXT_INSET
        return Rectangle(x, actionY, if (layout.truncated) metrics.stringWidth("…") + 4 else 48,
            layout.lineHeight)
    }

    fun buttonAt(inlay: Inlay<*>, point: Point): SourceInlayButton? = when {
        expandBounds(inlay)?.contains(point) == true -> SourceInlayButton.EXPAND
        buttonBounds(inlay, SourceInlayButton.PREVIOUS).contains(point) -> SourceInlayButton.PREVIOUS
        buttonBounds(inlay, SourceInlayButton.NEXT).contains(point) -> SourceInlayButton.NEXT
        else -> null
    }

    fun hintBounds(inlay: Inlay<*>): Rectangle {
        return controls(inlay).hint
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return sourceInlayControlBounds(left(inlay), 0, calcHeightInPixels(inlay), textWidth(inlay)).hint.maxX.toInt()
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val layout = labelLayout(inlay)
        return layout.lineHeight * (layout.lines.size + if (layout.actionLine) 1 else 0) + 4
    }

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val layout = labelLayout(inlay)
        val controls = sourceInlayControlBounds(left(inlay), targetRegion.y.toInt(),
            targetRegion.height.toInt(), layout.textWidth)
        val bounds = controls.hint
        if (hovered) {
            g.color = Color(130, 95, 200, 30)
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6)
        }
        g.font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.color = if (hovered) JBColor(Color(103, 58, 183), Color(189, 145, 242))
            else JBColor(Color(79, 72, 112), Color(173, 163, 199))
        val iconX = bounds.x + 8
        val iconSize = 7
        val iconY = bounds.y + (layout.lineHeight - iconSize) / 2 + 2
        g.fillOval(iconX, iconY, iconSize, iconSize)
        layout.lines.forEachIndexed { index, line ->
            g.drawString(line, iconX + LABEL_TEXT_INSET, bounds.y + index * layout.lineHeight + g.fontMetrics.ascent + 2)
        }
        if (layout.actionLine) {
            g.color = JBColor.BLUE
            g.drawString("收起", bounds.x + 8,
                bounds.y + layout.lines.size * layout.lineHeight + g.fontMetrics.ascent + 2)
        }
        UnderstandingControlPainter.paintNavigation(g, controls.previous, next = false,
            enabled = canPrevious, hovered = hoveredButton == SourceInlayButton.PREVIOUS)
        UnderstandingControlPainter.paintNavigation(g, controls.next, next = true,
            enabled = canNext, hovered = hoveredButton == SourceInlayButton.NEXT)
    }

    private companion object {
        const val LABEL_TEXT_INSET = 16
    }

}

/** 旧记录可能把 circled number 当装饰符存进 label；显示时去掉，binding 保留原文。 */
internal fun sourceInlayDisplayLabel(label: String): String =
    label.replaceFirst(Regex("^\\s*[①-⑳]\\s*"), "").ifBlank { label.trim() }

/** 按编辑器实际字形宽度折行，保留全部字符供展开查看。 */
internal fun wrapSourceInlayLabel(value: String, width: Int, measure: (String) -> Int): List<String> {
    val result = mutableListOf<String>()
    value.split('\n').forEach { paragraph ->
        var line = ""
        paragraph.codePoints().forEach { point ->
            val glyph = String(Character.toChars(point))
            if (line.isNotEmpty() && measure(line + glyph) > width) {
                result += line
                line = glyph
            } else line += glyph
        }
        result += line
    }
    return result
}
