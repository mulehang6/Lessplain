package com.lessplain.tutorial

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** 可见文字的每个 UTF-16 单元保留其 Markdown 偏移，样式不参与选区计数。 */
internal data class MappedText(val text: String, val offsets: List<Int>, val styles: List<TextStyle> = emptyList()) {
    fun source(index: Int): Int = offsets[index.coerceIn(0, offsets.lastIndex)]
}

internal data class TextStyle(val start: Int, val end: Int, val kind: String, val href: String? = null)
internal data class MarkdownContent(val start: Int, val end: Int, val kind: String, val text: MappedText, val language: String = "", val resourceId: String = "")

/** 使用平台 GFM 解析器确定块边界，再生成保留源范围的有限预览模型。 */
internal fun parseMarkdown(markdown: String): List<MarkdownContent> {
    if (markdown.isEmpty()) return emptyList()
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    return root.children.filter { it.endOffset > it.startOffset && markdown.substring(it.startOffset, it.endOffset).isNotBlank() }.map { node ->
        val start = node.startOffset
        val raw = markdown.substring(start, node.endOffset)
        val end = node.endOffset
        val resource = Regex("^!\\[.*?]\\(lessplain://(diagram|simulation)/([A-Za-z0-9_.-]+)\\)$").matchEntire(raw.trim())
        when {
            resource != null -> MarkdownContent(start, end, resource.groupValues[1], mappedPlain(raw, start), resourceId = resource.groupValues[2])
            raw.startsWith("```") || raw.startsWith("~~~") -> {
                val first = raw.indexOf('\n').let { if (it < 0) raw.length else it + 1 }
                val last = if (raw.trimEnd().lineSequence().last().matches(Regex("(`{3,}|~{3,})"))) raw.lastIndexOf('\n').coerceAtLeast(first) else raw.length
                MarkdownContent(start, end, "code", mappedPlain(raw.substring(first, last), start + first), raw.lineSequence().first().drop(3).trim())
            }
            node.type.toString().contains("TABLE") -> MarkdownContent(start, end, "table", mappedPlain(raw, start))
            else -> {
                val prefix = Regex("^(#{1,6})\\s+").find(raw)
                val contentStart = prefix?.value?.length ?: 0
                MarkdownContent(start, end, if (prefix != null) "heading${prefix.groupValues[1].length}" else "text", inlineMarkdown(raw.drop(contentStart), start + contentStart))
            }
        }
    }
}

internal fun mappedPlain(text: String, offset: Int) = MappedText(text, (0..text.length).map { offset + it })

internal fun inlineMarkdown(raw: String, sourceStart: Int): MappedText {
    val text = StringBuilder()
    val offsets = mutableListOf<Int>()
    val styles = mutableListOf<TextStyle>()
    fun append(value: String, start: Int, style: String? = null, href: String? = null) {
        val begin = text.length
        text.append(value)
        offsets += value.indices.map { sourceStart + start + it }
        if (style != null) styles += TextStyle(begin, text.length, style, href)
    }
    val pattern = Regex("\\[([^]\\n]+)]\\(([^)]+)\\)|`([^`\\n]+)`|\\*\\*([^*]+)\\*\\*|__([^_]+)__|\\*([^*\\n]+)\\*")
    var cursor = 0
    pattern.findAll(raw).forEach { match ->
        append(raw.substring(cursor, match.range.first), cursor)
        val group = listOf(1, 3, 4, 5, 6).first { match.groups[it] != null }
        val value = requireNotNull(match.groups[group])
        append(value.value, value.range.first, when (group) { 1 -> "link"; 3 -> "code"; 6 -> "emphasis"; else -> "strong" }, if (group == 1) match.groupValues[2] else null)
        cursor = match.range.last + 1
    }
    append(raw.substring(cursor), cursor)
    offsets += sourceStart + raw.length
    return MappedText(text.toString(), offsets, styles)
}
