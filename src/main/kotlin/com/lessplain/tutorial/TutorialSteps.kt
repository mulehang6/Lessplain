package com.lessplain.tutorial

/** 讲解点属于原正文；稳定标记与主锚点不依赖段落、行号或普通链接的先后。 */
internal data class TutorialStep(
    val id: String,
    val sourceId: String?,
    val markerStart: Int,
    val start: Int,
    val end: Int,
) {
    val range get() = start until end
}

internal fun tutorialSteps(markdown: String, strict: Boolean = false): List<TutorialStep> {
    if (!markdown.contains("<!-- lessplain:step")) return emptyList()
    val markers = parseMarkdown(markdown).filter { it.kind == "step" }
    val pattern = Regex("<!-- lessplain:step ([A-Za-z0-9_.-]+)(?: source=(src_[A-Za-z0-9_-]+))? -->")
    val steps = markers.mapIndexedNotNull { index, block ->
        val match = pattern.matchEntire(markdown.substring(block.start, block.end).trim())
        if (match == null) {
            tutorialRequire(!strict, "INVALID_STEP", "markdown", "讲解点格式应为 <!-- lessplain:step 稳定ID source=src_ID -->，无源码时省略 source")
            return@mapIndexedNotNull null
        }
        val end = markers.getOrNull(index + 1)?.start ?: markdown.length
        if (strict) tutorialRequire(markdown.substring(block.end, end).isNotBlank(), "EMPTY_STEP", "markdown", "讲解点需要正文")
        TutorialStep(match.groupValues[1], match.groupValues[2].ifEmpty { null }, block.start, block.end, end)
    }
    if (strict) uniqueIds(steps.map { it.id }, "markdown.steps")
    return steps
}

/** 普通链接与讲解点的主锚点一起参与来源校验和持久化。 */
internal fun tutorialSourceIds(markdown: String): List<String> = (
    Regex("lessplain://source/([^\\s)]+)").findAll(markdown).map { it.groupValues[1] }.toList() +
        tutorialSteps(markdown).mapNotNull { it.sourceId }
    ).distinct()

/** 整点移动时按稳定身份迁移批注，重复引文也不会落到另一个讲解点。 */
internal fun movedStepAnnotation(note: TutorialAnnotation, before: String, after: String): TutorialAnnotation? {
    if (note.orphaned) return null
    val old = tutorialSteps(before).singleOrNull { note.start >= it.start && note.end <= it.end } ?: return null
    val next = tutorialSteps(after).singleOrNull { it.id == old.id } ?: return null
    if (before.substring(old.start, old.end).trimEnd() != after.substring(next.start, next.end).trimEnd()) return null
    val start = next.start + note.start - old.start
    val end = start + note.end - note.start
    if (end > after.length || after.substring(start, end) != note.quote) return null
    return note.copy(start = start, end = end)
}

/** 一次连续滚轮手势最多换一点，长点先正常滚动，选区和缩放不进入此策略。 */
internal class TutorialStepWheel {
    private var lastEvent = Long.MIN_VALUE
    private var changed = false
    private var direction = 0
    private var accumulated = 0.0

    fun turn(rotation: Double, atBoundary: Boolean, time: Long): Int {
        val nextDirection = if (rotation > 0) 1 else if (rotation < 0) -1 else return 0
        if (lastEvent == Long.MIN_VALUE || time - lastEvent > 220 || direction != nextDirection) {
            changed = false
            accumulated = 0.0
        }
        lastEvent = time
        direction = nextDirection
        if (!atBoundary) { accumulated = 0.0; return 0 }
        if (changed) return 0
        accumulated += kotlin.math.abs(rotation)
        if (accumulated < 1.0) return 0
        changed = true
        return direction
    }
}
