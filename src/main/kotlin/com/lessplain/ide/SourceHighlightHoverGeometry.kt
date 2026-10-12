package com.lessplain.ide

import java.awt.Rectangle

/** 仅连接标签下缘与代码上缘的短间隙，不能跨越其他提示或扩大到两侧空白。 */
internal fun sourceHighlightHoverBridge(hint: Rectangle?, block: Rectangle?, maxGap: Int): Rectangle? {
    if (hint == null || block == null || hint.isEmpty || block.isEmpty) return null
    val top = hint.y + hint.height
    val gap = block.y - top
    if (gap <= 0 || gap > maxGap) return null
    val left = maxOf(hint.x, block.x)
    val right = minOf(hint.x + hint.width, block.x + block.width)
    if (right <= left) return null
    return Rectangle(left, top, right - left, gap)
}
