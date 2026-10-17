package com.lessplain.model

import kotlinx.serialization.Serializable

/** IntelliJ Document 的 UTF-16 半开区间，仅由原生选区捕获，不能用文本搜索代替。 */
@Serializable
data class SourceSelectionRange(val startOffset: Int, val endOffset: Int) {
    fun matches(content: String, text: String): Boolean =
        startOffset >= 0 && endOffset > startOffset && endOffset <= content.length &&
            endOffset - startOffset == text.length && content.regionMatches(startOffset, text, 0, text.length)
}
