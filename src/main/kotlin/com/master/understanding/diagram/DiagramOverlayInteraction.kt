package com.master.understanding.diagram

import java.awt.Point
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

internal data class DiagramTitleState(var hovered: String? = null, var pressed: String? = null, var selected: String? = null)

/** Canvas-owned transient overlays and feedback, separate from graph layout and browse state. */
internal class DiagramOverlayInteraction(private val diagram: UnderstandingDiagram) : AutoCloseable {
    val titles = DiagramTitleState()
    val clusterTargets = mutableMapOf<String, String>()
    private val resources = mutableListOf<AutoCloseable>()
    var anchor = Point()
        private set

    fun own(resource: AutoCloseable) { resources += resource }

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
        resources.asReversed().forEach { it.close() }
        resources.clear()
        clusterTargets.clear()
    }
}

internal fun UnderstandingDiagram.showNodeMenu(nodeId: String, point: Point) {
    overlays.pressed(point)
    JPopupMenu().apply {
        add(JMenuItem("添加注释").apply {
            isEnabled = addNoteCallbacks.isNotEmpty()
            addActionListener { addNoteCallbacks.toList().forEach { it(nodeId) } }
        })
        show(this@showNodeMenu, point.x, point.y)
    }
}
