package com.lessplain.diagram

import java.awt.Point
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

internal data class DiagramTitleState(var hovered: String? = null, var pressed: String? = null, var selected: String? = null)

/** 画布管理临时覆盖层和反馈，与图布局及浏览状态分开。 */
internal class DiagramOverlayInteraction(private val diagram: UnderstandingDiagram) : AutoCloseable {
    val titles = DiagramTitleState()
    val clusterTargets = mutableMapOf<String, String>()
    private var edgeDetails: AutoCloseable? = null
    private val resources = mutableListOf<AutoCloseable>()
    var anchor = Point()
        private set

    fun own(resource: AutoCloseable) { resources += resource }

    fun replaceEdgeDetails(resource: AutoCloseable) {
        edgeDetails?.close()
        edgeDetails = resource
    }

    fun moved(point: Point) {
        anchor = point
        val title = diagram.clusterTitleAt(point)
        if (titles.hovered != title) { titles.hovered = title; diagram.repaint() }
    }

    fun exited() { titles.hovered = null }

    fun pressed(point: Point) {
        anchor = point
        titles.pressed = diagram.clusterTitleAt(point)
        diagram.repaint()
    }

    fun released(point: Point, dragged: Boolean) {
        if (!dragged && titles.pressed == diagram.clusterTitleAt(point)) titles.selected = titles.pressed
        titles.pressed = null
        diagram.repaint()
    }

    fun rememberTarget(nodeId: String) {
        diagram.clusterSpecs.firstOrNull { nodeId in it.nodeIds }?.let { clusterTargets[it.id] = nodeId }
    }

    override fun close() {
        edgeDetails?.close()
        edgeDetails = null
        resources.asReversed().forEach { it.close() }
        resources.clear()
        clusterTargets.clear()
    }
}

internal fun UnderstandingDiagram.showNodeMenu(nodeId: String, point: Point) {
    overlays.pressed(point)
    JPopupMenu().apply {
        add(JMenuItem("重新选择来路").apply {
            isEnabled = routeNavigation != null
            addActionListener { routeNavigation?.complete(nodeId) }
        })
        add(JMenuItem("添加注释").apply {
            isEnabled = addNoteCallbacks.isNotEmpty()
            addActionListener { addNoteCallbacks.toList().forEach { it(nodeId) } }
        })
        show(this@showNodeMenu, point.x, point.y)
    }
}
