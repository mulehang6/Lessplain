package com.lessplain.tutorial

internal data class MarkdownResource(val start: Int, val end: Int, val kind: String, val id: String)

/** 块级解析后识别教程专用图片引用；转义内容与代码跨度保持为普通 Markdown。 */
internal fun resourceReferences(raw: String, sourceStart: Int): List<MarkdownResource> {
    val pattern = Regex("!\\[(?:[^]\\\\]|\\\\.)*]\\(lessplain://(diagram|simulation)/([A-Za-z0-9_.-]+)\\)")
    return buildList {
        var cursor = 0
        while (cursor < raw.length) {
            when (raw[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(raw.length)
                '`' -> {
                    val count = raw.substring(cursor).takeWhile { it == '`' }.length
                    val delimiter = "`".repeat(count)
                    var close = raw.indexOf(delimiter, cursor + count)
                    while (close >= 0 && (raw.getOrNull(close - 1) == '`' || raw.getOrNull(close + count) == '`')) {
                        close = raw.indexOf(delimiter, close + count)
                    }
                    cursor = if (close < 0) cursor + count else close + count
                }
                '!' -> {
                    val match = pattern.matchAt(raw, cursor)
                    if (match == null) cursor++ else {
                        add(MarkdownResource(sourceStart + cursor, sourceStart + match.range.last + 1,
                            match.groupValues[1], match.groupValues[2]))
                        cursor = match.range.last + 1
                    }
                }
                else -> cursor++
            }
        }
    }
}
