package com.lessplain.experiment.ide

import com.lessplain.experiment.model.BrowserHostKind
import com.lessplain.experiment.model.ViewRecord

/** 固定入口所属的问题树根；编辑器宿主仅能在该树内切换结果。 */
internal fun viewTreeRootRef(ref: String?, views: List<ViewRecord>): String? {
    if (ref == null) return null
    val byRef = views.associateBy(ViewRecord::ref)
    var current = byRef[ref] ?: return null
    val visited = mutableSetOf<String>()
    while (true) {
        if (!visited.add(current.ref)) return null
        val parentRef = current.parentRef ?: return current.ref
        current = byRef[parentRef] ?: return null
    }
}

private fun belongsToViewTree(rootRef: String?, candidateRef: String?, views: List<ViewRecord>): Boolean {
    if (rootRef == null || candidateRef == null) return false
    val byRef = views.associateBy(ViewRecord::ref)
    var current = byRef[candidateRef] ?: return false
    val visited = mutableSetOf<String>()
    while (visited.add(current.ref)) {
        if (current.ref == rootRef) return true
        val parentRef = current.parentRef ?: return false
        current = byRef[parentRef] ?: return false
    }
    return false
}

/** 编辑器入口固定问题树；工具窗口仍可以选择任意结果或主页。 */
internal fun resolveBrowserViewSelection(
    kind: BrowserHostKind,
    fixedTreeRootRef: String?,
    requestedRef: String?,
    views: List<ViewRecord>,
): String? = when (kind) {
    BrowserHostKind.TOOL_WINDOW -> requestedRef
    BrowserHostKind.EDITOR -> {
        check(requestedRef != null) { "编辑器页面不能显示历史记录" }
        check(belongsToViewTree(fixedTreeRootRef, requestedRef, views)) {
            "编辑器页面不能切换到另一个理解树"
        }
        requestedRef
    }
}
