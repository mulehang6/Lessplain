package com.lessplain.tutorial

/** both 是显示状态，不增加第三种批注或改变持久化格式。 */
internal data class AnnotationInkRun(val start: Int, val end: Int, val kind: String) {
    val seed get() = start * 31 + end
}

/** 先合并同类覆盖，再按真正的交集着色，避免重复笔记叠深或污染未重叠文字。 */
internal fun annotationInkRuns(items: List<TutorialAnnotation>): List<AnnotationInkRun> {
    val events = sortedMapOf<Int, IntArray>()
    items.filter { !it.orphaned && it.end > it.start }.forEach { item ->
        val kind = if (item.kind == "note") 0 else 1
        events.getOrPut(item.start) { IntArray(2) }[kind]++
        events.getOrPut(item.end) { IntArray(2) }[kind]--
    }
    val runs = mutableListOf<AnnotationInkRun>()
    val counts = IntArray(2)
    var previous = 0
    events.forEach { (offset, delta) ->
        val kind = when { counts[0] > 0 && counts[1] > 0 -> "both"; counts[0] > 0 -> "note"; counts[1] > 0 -> "question"; else -> null }
        if (kind != null && offset > previous) {
            val last = runs.lastOrNull()
            if (last?.kind == kind && last.end == previous) runs[runs.lastIndex] = last.copy(end = offset)
            else runs += AnnotationInkRun(previous, offset, kind)
        }
        counts[0] += delta[0]; counts[1] += delta[1]
        previous = offset
    }
    return runs
}

/** 每类独立选择鼠标所在处最具体的原范围，混合笔迹不伪造一个新的批注范围。 */
internal fun annotationsAt(items: List<TutorialAnnotation>, source: Int): Map<String, List<TutorialAnnotation>> =
    items.filter { !it.orphaned && source in it.start until it.end }.groupBy { it.kind }.mapValues { (_, values) ->
        val smallest = values.minBy { it.end - it.start }
        values.filter { sameAnnotationGroup(it, smallest) }
    }

internal fun sameAnnotationGroup(a: TutorialAnnotation, b: TutorialAnnotation) =
    a.kind == b.kind && a.start == b.start && a.end == b.end && a.quote == b.quote && a.parentId == b.parentId
