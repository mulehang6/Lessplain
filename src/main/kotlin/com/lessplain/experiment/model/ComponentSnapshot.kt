package com.lessplain.experiment.model

import com.lessplain.model.SourceSelectionRange
import kotlinx.serialization.Serializable

/** 页面组件的打开时快照。与静态教学资源、真实项目文件分别标识。 */
@Serializable
data class ComponentSnapshot(
    val id: String,
    val elementId: String,
    val componentVersion: String,
    val content: CodeSample,
    val version: String,
    val originalText: String? = null,
)

@Serializable
data class ComponentSelection(val snapshot: ComponentSnapshot, val selectionRange: SourceSelectionRange)
