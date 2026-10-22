package com.lessplain.ide

import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256

/** 原生选区的上下文及其中的精确范围，不依赖关注词自身唯一。 */
internal data class SourceFocus(val anchor: SourceAnchor, val start: Int, val end: Int)

internal fun captureSourceFocus(source: String, path: String, start: Int, end: Int, symbol: String? = null): SourceFocus {
    require(start >= 0 && start < end && end <= source.length)
    require(source.substring(start, end).isNotBlank())
    var padding = 48
    while (true) {
        val left = (start - padding).coerceAtLeast(0)
        val right = (end + padding).coerceAtMost(source.length)
        val context = source.substring(left, right)
        if (source.indexOf(context) == source.lastIndexOf(context)) return SourceFocus(SourceAnchor(path, symbol, context, sha256(source)), start - left, end - left)
        padding = (padding * 2).coerceAtMost(source.length)
    }
}
