package com.master.understanding.diagram

import com.master.understanding.ide.GraphNodePosition
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.pow

/** Drag, pan, hover, and wheel behavior for one graph surface. */
internal class DiagramMouseInteraction(private val diagram: UnderstandingDiagram) : MouseAdapter() {
    private var pressPoint: Point? = null
    private var previousDragPoint: Point? = null
    private var draggedNodeId: String? = null
    private var draggedExampleClusterId: String? = null
    private var dragged = false

    override fun mousePressed(event: MouseEvent) {
        if (event.isPopupTrigger || event.button == MouseEvent.BUTTON3) {
            diagram.nodeAt(event.point)?.let { diagram.showNodeMenu(it, event.point) }
            return
        }
        if (event.button != MouseEvent.BUTTON1) return
        diagram.requestFocusInWindow()
        diagram.overlays.pressed(event.point)
        pressPoint = event.point
        previousDragPoint = event.point
        draggedNodeId = null
        draggedExampleClusterId = null
        if (diagram.copyActionAt(event.point) == null && diagram.questionActionAt(event.point) == null &&
            diagram.noteActionAt(event.point) == null) {
            draggedNodeId = diagram.nodeAt(event.point)
            if (draggedNodeId == null) draggedExampleClusterId = diagram.examplePanelAt(event.point)
            if (draggedExampleClusterId == null) draggedNodeId = diagram.draggableNodeAt(event.point)
        }
        dragged = false
    }

    override fun mouseDragged(event: MouseEvent) {
        val previous = previousDragPoint ?: return
        if ((pressPoint?.distance(event.point) ?: 0.0) > 3.0) dragged = true
        if (!dragged) return
        diagram.cameraTimer.stop()
        val nodeId = draggedNodeId
        if (nodeId != null) {
            val b = diagram.layout.nodes[nodeId]?.bounds ?: return
            val position = GraphNodePosition(b.x + (event.x - previous.x) / diagram.zoom,
                b.y + (event.y - previous.y) / diagram.zoom)
            diagram.setNodePositions(diagram.nodePositions + (nodeId to position))
        } else if (draggedExampleClusterId != null) {
            val clusterId = draggedExampleClusterId ?: return
            val b = diagram.layout.clusters.firstOrNull { it.id == clusterId }?.exampleBounds ?: return
            val position = GraphNodePosition(b.x + (event.x - previous.x) / diagram.zoom,
                b.y + (event.y - previous.y) / diagram.zoom)
            diagram.setNodePositions(diagram.nodePositions + (examplePositionKey(clusterId) to position))
        } else {
            diagram.centerX -= (event.x - previous.x) / diagram.zoom
            diagram.centerY -= (event.y - previous.y) / diagram.zoom
        }
        previousDragPoint = event.point
        diagram.repaint()
    }

    override fun mouseReleased(event: MouseEvent) {
        if (event.button == MouseEvent.BUTTON1 && diagram.retryFailedLayout()) return
        if (event.button == MouseEvent.BUTTON1 && !dragged) {
            val copyNode = diagram.copyActionAt(event.point)
            val questionNode = diagram.questionActionAt(event.point)
            val noteNode = diagram.noteActionAt(event.point)
            val titleEdit = diagram.clusterEditAt(event.point)
            if (titleEdit != null) diagram.titleCallbacks.toList().forEach { it(titleEdit) }
            else if (noteNode != null) {
                if (diagram.hasNodeNote(noteNode)) {
                    diagram.noteCallbacks.toList().forEach { it(noteNode, diagram, event.point) }
                } else diagram.addNoteCallbacks.toList().forEach { it(noteNode) }
            }
            else if (copyNode != null) diagram.copyCallbacks.toList().forEach { it(copyNode) }
            else if (questionNode != null) diagram.questionCallbacks.toList().forEach { it(questionNode) }
            else {
                val node = diagram.nodeAt(event.point)
                val edge = diagram.candidateEdgeAt(event.point)
                if (edge != null) diagram.routeNavigation?.selectCandidateEdge(edge)
                else if (node != null) diagram.selectUnderstandingNode(node, notify = true)
                else diagram.routeNavigation?.cancel()
            }
        }
        previousDragPoint = null
        diagram.overlays.released(event.point, dragged)
        pressPoint = null
        if (dragged) {
            if (draggedNodeId != null || draggedExampleClusterId != null) diagram.commitNodePositions()
            else diagram.queueViewportSave()
        }
        draggedNodeId = null
        draggedExampleClusterId = null
    }

    override fun mouseClicked(event: MouseEvent) {
        if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2) {
            diagram.clusterTitleAt(event.point)?.let { id ->
                diagram.titleCallbacks.toList().forEach { it(id) }
                event.consume()
            }
        }
    }

    override fun mouseMoved(event: MouseEvent) {
        val hover = diagram.hoverNodeAt(event.point)
        val oldHover = diagram.hoveredNodeId
        val oldAction = diagram.hoveredAction
        val edge = diagram.candidateEdgeAt(event.point)
        if (edge != null) diagram.routeNavigation?.previewEdge(edge)
        else if (hover in diagram.choiceTargets) diagram.routeNavigation?.previewCandidateNode(hover)
        diagram.hoveredNodeId = hover
        diagram.hoveredAction = when {
            diagram.copyActionAt(event.point) != null -> DiagramNodeAction.COPY
            diagram.questionActionAt(event.point) != null -> DiagramNodeAction.QUESTIONS
            diagram.noteActionAt(event.point) != null -> DiagramNodeAction.NOTE
            else -> null
        }
        diagram.overlays.moved(event.point)
        if (oldHover != hover || oldAction != diagram.hoveredAction) diagram.repaint()
        diagram.cursor = if (edge != null || hover != null || diagram.hoveredAction != null || diagram.examplePanelAt(event.point) != null ||
            diagram.clusterTitleAt(event.point) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        else Cursor.getDefaultCursor()
    }

    override fun mouseExited(event: MouseEvent) {
        diagram.hoveredNodeId = null
        diagram.hoveredAction = null
        diagram.overlays.exited()
        diagram.repaint()
    }

    override fun mouseWheelMoved(event: MouseWheelEvent) {
        diagram.cameraTimer.stop()
        val before = diagram.screenToWorld(event.point)
        diagram.zoom *= 1.12.pow(-event.preciseWheelRotation)
        val after = diagram.screenToWorld(event.point)
        diagram.centerX += before.x - after.x
        diagram.centerY += before.y - after.y
        diagram.repaint()
        diagram.queueViewportSave()
    }
}
