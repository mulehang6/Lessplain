package com.lessplain.diagram

/** 激活和选择分离；无讲解时保留原来的追问键盘入口。 */
internal fun UnderstandingDiagram.onNodeActivated(callback: (String) -> Boolean): AutoCloseable {
    activatedCallbacks += callback
    return AutoCloseable { activatedCallbacks -= callback }
}

internal fun UnderstandingDiagram.activateNode(id: String): Boolean = activatedCallbacks.toList().any { it(id) }
