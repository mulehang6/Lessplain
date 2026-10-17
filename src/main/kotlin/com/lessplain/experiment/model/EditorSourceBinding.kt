package com.lessplain.experiment.model

/** 每个独立 JCEF 页面都有自己的宿主身份和导航状态。 */
enum class BrowserHostKind(val wireValue: String) {
    TOOL_WINDOW("toolwindow"),
    EDITOR("editor"),
}

enum class SourceStepDirection(val wireValue: String) {
    PREVIOUS("previous"),
    NEXT("next"),
}

/**
 * Immutable, in-memory provenance captured when a source navigation opens the
 * native question editor. It is deliberately not part of the serialized model.
 */
data class EditorSourceBinding(
    val viewRef: String,
    val revision: Int,
    val elementId: String?,
    val nodeId: String?,
    val sourceId: String,
    val label: String,
    val state: JsonPayload,
    val sourceSnapshot: ResolvedSource,
    val componentType: String? = null,
    val demoId: String? = null,
    /** 页面宿主只用于把源码步进回送到产生该 binding 的浏览器实例。 */
    val hostId: String? = null,
    val hostKind: BrowserHostKind? = null,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
)
