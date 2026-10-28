package com.lessplain.diagram

import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent

/** 安装输入处理器；画布尚无尺寸时推迟的布局请求在就绪后重试。 */
internal fun UnderstandingDiagram.installDiagramInteractions() {
    DiagramKeyboardActions.install(this, { DiagramKeyboardActions.move(this, it) },
        onCopy = { currentNodeId?.let { id -> copyCallbacks.toList().forEach { it(id) } } },
        onQuestions = questions@ {
            overlays.titles.focused?.takeIf { it in questionClusterIds }?.let { id -> originCallbacks.toList().forEach { it(id) }; return@questions }
            if (routeNavigation?.confirmCandidate() == true) return@questions
            currentNodeId?.takeIf { (questionCounts[it] ?: 0) > 0 }?.let { id ->
                questionCallbacks.toList().forEach { it(id) }
            }
        }, onCancel = { routeNavigation?.cancel() })
    getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("alt T"), "focus-group-title")
    actionMap.put("focus-group-title", object : javax.swing.AbstractAction() {
        override fun actionPerformed(event: java.awt.event.ActionEvent) {
            val ids = clusterSpecs.map { it.id }.filter { it in questionClusterIds }
            if (ids.isNotEmpty()) overlays.titles.focused = ids[(ids.indexOf(overlays.titles.focused) + 1) % ids.size]
            repaint()
        }
    })
    addFocusListener(object : java.awt.event.FocusAdapter() {
        override fun focusLost(event: java.awt.event.FocusEvent) { overlays.titles.focused = null; repaint() }
    })
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
