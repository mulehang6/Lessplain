package com.master.lessplain.ide

/** 位置找回不能消除证据变化提示，也不能恢复旧 inline。 */
internal fun sourceEvidenceMessage(located: LocatedSource?): String? = when {
    located == null -> "源码定位失败：当前文件无法读取"
    located.location == LocationStatus.RELOCATED && located.evidence == EvidenceStatus.CHANGED ->
        "仅定位到所属函数，原片段已变化"
    located.location == LocationStatus.AMBIGUOUS -> "源码定位失败：存在多个候选"
    located.location == LocationStatus.UNRESOLVED -> "源码定位失败：当前文件中找不到对应证据"
    located.evidence == EvidenceStatus.CHANGED -> "原片段已变化"
    else -> null
}

/** 使用随编辑移动的范围，只因当前片段改变而撤销解释，不因无关编辑撤销。 */
internal fun sourceExcerptChanged(content: CharSequence, start: Int, end: Int, original: String): Boolean =
    start < 0 || end < start || end > content.length || content.subSequence(start, end).toString() != original
