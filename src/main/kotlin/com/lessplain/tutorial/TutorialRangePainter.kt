package com.lessplain.tutorial

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent
import javax.swing.text.Position
import javax.swing.text.Utilities

/** 按视觉行绘制完整背景，不随 StyledDocument 的语法叶子 View 重复切分。 */
internal class TutorialRangePainter(private val draw: (Graphics2D, Rectangle) -> Unit) : Highlighter.HighlightPainter {
    override fun paint(graphics: Graphics, start: Int, end: Int, bounds: java.awt.Shape, component: JTextComponent) {
        val g = graphics.create() as Graphics2D
        try { tutorialVisualRows(component, start, end).forEach { draw(g, it) } } finally { g.dispose() }
    }
}

/** 使用文本 UI 的正反向位置映射保留折行边界，不以字符宽度猜测中英文位置。 */
internal fun tutorialVisualRows(component: JTextComponent, start: Int, end: Int): List<Rectangle> {
    val result = mutableListOf<Rectangle>()
    var offset = start.coerceAtLeast(0)
    val limit = end.coerceAtMost(component.document.length)
    while (offset < limit) {
        val first = component.modelToView2D(offset) ?: break
        val rowEnd = Utilities.getRowEnd(component, offset)
        if (rowEnd < offset) break
        val next = minOf(limit, rowEnd + 1)
        val last = component.ui.modelToView2D(component, next, Position.Bias.Backward) ?: break
        val right = if (kotlin.math.abs(first.y - last.y) < 1) last.x else {
            // 段落结束符本身不画背景，前一位置就在可见行尾。
            component.modelToView2D((next - 1).coerceAtLeast(offset))?.x ?: first.x
        }
        result += Rectangle(first.x.toInt(), first.y.toInt(), (right - first.x).toInt().coerceAtLeast(3), first.height.toInt())
        offset = next
    }
    return result
}
