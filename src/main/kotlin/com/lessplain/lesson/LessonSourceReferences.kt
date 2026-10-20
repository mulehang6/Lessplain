package com.lessplain.lesson

import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256

/** 截取足以唯一定位的上下文，不要求被选中的单词自身唯一。 */
internal fun captureLessonFocus(source: String, path: String, start: Int, end: Int, symbol: String? = null): LessonSourceFocus {
    require(start >= 0 && start < end && end <= source.length)
    require(source.substring(start, end).isNotBlank())
    var padding = 48
    while (true) {
        val left = (start - padding).coerceAtLeast(0)
        val right = (end + padding).coerceAtMost(source.length)
        val context = source.substring(left, right)
        if (uniqueLessonSourceOffset(source, context) != null) {
            return LessonSourceFocus(SourceAnchor(path, symbol, context, sha256(source)), start - left, end - left)
        }
        padding = (padding * 2).coerceAtMost(source.length)
    }
}

internal fun resolveLessonFocus(source: String, focus: LessonSourceFocus): IntRange? {
    validateLessonFocus(focus)
    val offset = uniqueLessonSourceOffset(source, focus.anchor.text) ?: return null
    return (offset + focus.start) until (offset + focus.end)
}

internal fun focusText(focus: LessonSourceFocus): String = focus.anchor.text.substring(focus.start, focus.end)

/** 悬停条目包含精确教材位置；计数按实际批注身份去重。 */
internal data class LessonSourceEntry(
    val lessonId: String,
    val title: String,
    val blockId: String?,
    val owner: String?,
    val focus: LessonSourceFocus,
    val label: String,
    val excerpt: String,
    val annotations: List<LessonAnnotation> = emptyList(),
)

internal fun lessonSourceEntries(state: LessonState): List<LessonSourceEntry> = state.lessons.flatMap { lesson ->
    val notes = state.annotations.filter { it.lessonId == lesson.id }
    val entries = mutableListOf<LessonSourceEntry>()
    lesson.origin.focus?.let { entries += LessonSourceEntry(lesson.id, lesson.title, null, null, it, focusText(it), "") }
    fun add(blocks: List<LessonBlock>, owner: String?) {
        blocks.forEach { block -> block.sourceBindings.orEmpty().forEach { binding ->
            entries += LessonSourceEntry(lesson.id, lesson.title, block.id, owner, binding.focus, binding.label,
                lessonBlockText(block).take(220))
        } }
    }
    add(lesson.blocks, null)
    notes.forEach { add(it.answer, it.id) }
    fun forTarget(target: LessonTarget, visited: Set<String> = emptySet()): List<LessonSourceEntry> {
        val blocks = target.annotationId?.let { id -> notes.firstOrNull { it.id == id }?.answer } ?: lesson.blocks
        val block = blocks.firstOrNull { it.id == target.blockId }
        val matches = block?.sourceBindings.orEmpty().filter { binding ->
            binding.start == null || binding.end == null || target.start < binding.end && target.end > binding.start
        }
        if (matches.isNotEmpty()) return entries.filter { entry ->
            entry.blockId == target.blockId && entry.owner == target.annotationId && matches.any { it.focus == entry.focus }
        }
        val parent = target.annotationId?.takeUnless { it in visited }?.let { id -> notes.firstOrNull { it.id == id } }
        if (parent != null) return forTarget(parent.target, visited + parent.id)
        return entries.filter { it.blockId == null }
    }
    val attached = mutableMapOf<LessonSourceEntry, MutableList<LessonAnnotation>>()
    notes.forEach { note -> forTarget(note.target).forEach { attached.getOrPut(it) { mutableListOf() } += note } }
    entries.map { it.copy(annotations = attached[it].orEmpty().distinctBy(LessonAnnotation::id)) }
}

/** 重叠时先显示最窄的实际范围；同范围的多个教材由 UI 合并预览。 */
internal fun <T> lessonEntriesAt(entries: List<Pair<IntRange, T>>, offset: Int): List<T> =
    entries.filter { offset in it.first }.sortedBy { it.first.last - it.first.first }.map { it.second }
