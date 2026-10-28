package com.lessplain.tutorial

/** 保留代码每个字符的源偏移，同时移除 Markdown 围栏与规范缩进。 */
internal fun markdownCode(raw: String, start: Int, end: Int, fenced: Boolean): MarkdownContent {
    val lines = Regex("[^\\n]*(?:\\n|$)").findAll(raw).filter { it.value.isNotEmpty() }.toList()
    val opening = if (fenced) Regex("^( {0,3})(`{3,}|~{3,})([^\\r\\n]*)").find(raw) else null
    val indent = opening?.groupValues?.get(1)?.length ?: 4
    val fence = opening?.groupValues?.get(2).orEmpty()
    val closing = if (fence.isEmpty()) null else Regex(" {0,3}${Regex.escape(fence.first().toString())}{${fence.length},} *\\r?")
    val body = if (fenced) lines.drop(1).let { values ->
        if (values.lastOrNull()?.value?.trimEnd('\n')?.let { closing?.matches(it) } == true) values.dropLast(1) else values
    } else lines
    val text = StringBuilder()
    val offsets = mutableListOf<Int>()
    body.forEachIndexed { index, line ->
        val prefix = line.value.takeWhile { it == ' ' }.length.coerceAtMost(indent)
        val until = if (index == body.lastIndex) line.value.trimEnd('\n', '\r').length else line.value.length
        for (position in prefix until until) {
            text.append(line.value[position]); offsets += start + line.range.first + position
        }
    }
    offsets += offsets.lastOrNull()?.plus(1) ?: (start + (body.firstOrNull()?.range?.first ?: raw.length))
    return MarkdownContent(start, end, "code", MappedText(text.toString(), offsets), opening?.groupValues?.get(3)?.trim().orEmpty())
}
