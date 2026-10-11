package com.lessplain.diagram

import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent

/** 安装输入处理器；画布尚无尺寸时推迟的布局请求在就绪后重试。 */
internal fun UnderstandingDiagram.installDiagramInteractions() {
    DiagramKeyboardActions.install(this, { DiagramKeyboardActions.move(this, it) },
        onCopy = { currentNodeId?.let { id -> copyCallbacks.toList().forEach { it(id) } } },
        onQuestions = questions@ {
            if (routeNavigation?.confirmCandidate() == true) return@questions
            currentNodeId?.takeIf { (questionCounts[it] ?: 0) > 0 }?.let { id ->
                questionCallbacks.toList().forEach { it(id) }
            }
        }, onCancel = { routeNavigation?.cancel() })
    val mouse = DiagramMouseInteraction(this)
    addMouseListener(mouse)
    addMouseMotionListener(mouse)
    addMouseWheelListener(mouse)
    addComponentListener(object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) {
            if (disposed || width <= 0 || height <= 0) return
            if (layoutRequestPending) requestLayout()
            else if (!initializedViewport && !layoutBusy && layoutFailure == null) initializeViewport()
            applyPendingClusterFocus()
            if (autoFollowNode) pendingCenterNodeId?.let { centerOnNode(it) }
        }
    })
    addHierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
            if (!isShowing) routeNavigation?.cancel()
            updateTimer()
        }
    }
}
