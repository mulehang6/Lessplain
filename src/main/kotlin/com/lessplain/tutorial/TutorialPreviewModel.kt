package com.lessplain.tutorial

/** 预览只有一个连续文本坐标系；格式符和资源控件不改变源码坐标。 */
internal class TutorialPreviewModel(markdown: String) {
    data class Part(val block: MarkdownContent, val from: Int, val to: Int, val mapped: MappedText)
    val parts: List<Part>
    val text: String
    private val offsets: List<Int>

    init {
        val visible = StringBuilder()
        val source = mutableListOf<Int>()
        parts = parseMarkdown(markdown).map { block ->
            val mapped = when (block.kind) {
                "diagram", "simulation" -> MappedText("\uFFFC", listOf(block.start, block.end))
                "table" -> tableText(block)
                else -> block.text
            }
            val from = visible.length
            visible.append(mapped.text)
            source += mapped.offsets.take(mapped.text.length)
            val part = Part(block, from, visible.length, mapped)
            visible.append("\n\n")
            source += listOf(block.end, block.end)
            part
        }
        source += markdown.length
        text = visible.toString()
        offsets = source
    }

    fun sourceAt(position: Int): Int = offsets[position.coerceIn(0, offsets.lastIndex)]
    fun visibleAt(source: Int): Int {
        var low = 0
        var high = offsets.lastIndex
        while (low < high) {
            val middle = (low + high) / 2
            if (offsets[middle] < source) low = middle + 1 else high = middle
        }
        return low
    }
    fun selection(start: Int, end: Int): IntRange? {
        if (end <= start) return null
        val a = boundary(start, false)
        val b = boundary(end, true)
        val from = sourceAt(a)
        val to = parts.firstOrNull { b == it.to && it.block.kind in setOf("diagram", "simulation") }?.block?.end
            ?: (sourceAt((b - 1).coerceAtLeast(a)) + 1)
        return if (to > from) from until to else null
    }
    private fun boundary(index: Int, end: Boolean): Int {
        val i = index.coerceIn(0, text.length)
        return if (i in 1 until text.length && text[i].isLowSurrogate() && text[i - 1].isHighSurrogate()) i + if (end) 1 else -1 else i
    }
}

/** 表格文字保留在同一文档中，Tab 分列，跨单元格和段落仍是正常连续选区。 */
private fun tableText(block: MarkdownContent): MappedText {
    val text = StringBuilder()
    val offsets = mutableListOf<Int>()
    val styles = mutableListOf<TextStyle>()
    var source = block.start
    block.text.text.lines().forEach { line ->
        if (line.isNotBlank() && !line.trim().matches(Regex("\\|?[ :|-]+\\|?"))) {
            var from = if (line.startsWith('|')) 1 else 0
            var column = 0
            while (from < line.length) {
                val end = line.indexOf('|', from).let { if (it < 0) line.length else it }
                val raw = line.substring(from, end)
                val mapped = inlineMarkdown(raw.trim(), source + from + raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))
                if (column++ > 0) { text.append('\t'); offsets += source + from - 1 }
                val base = text.length
                text.append(mapped.text); offsets += mapped.offsets.take(mapped.text.length)
                styles += mapped.styles.map { it.copy(start = it.start + base, end = it.end + base) }
                from = end + 1
            }
            text.append('\n'); offsets += source + line.length
        }
        source += line.length + 1
    }
    offsets += block.end
    return MappedText(text.toString(), offsets, styles)
}
