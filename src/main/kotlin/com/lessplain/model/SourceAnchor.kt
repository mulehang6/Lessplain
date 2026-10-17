package com.lessplain.model

/** 固定源码片段；行号只作提示，不是源码身份。 */
data class SourceAnchor(
    val path: String,
    val symbol: String?,
    val text: String,
    val version: String,
    val lineHint: Int? = null,
    val fingerprint: String? = null,
    val selectionRange: SourceSelectionRange? = null,
)
