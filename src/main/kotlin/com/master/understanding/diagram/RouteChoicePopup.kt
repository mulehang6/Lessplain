package com.master.understanding.diagram

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.master.understanding.core.StepDirection
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.UnderstandingGraph
import java.awt.Component
import java.awt.EventQueue
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Editor-anchored choices share preview and exact-edge completion with the active graph owner. */
internal fun interface RouteChoicePresenter {
    fun show(
        graph: UnderstandingGraph,
        direction: StepDirection,
        edges: List<GraphEdge>,
        component: Component,
        point: Point,
        preview: (String?) -> Unit,
        finished: (String?) -> Unit,
    ): AutoCloseable
}

/** Each item retains its exact relationship, including parallel conditional edges. */
internal data class RouteChoiceItem(val edgeId: String, val label: String) {
    override fun toString(): String = label
}

internal fun routeChoiceItems(graph: UnderstandingGraph, edges: List<GraphEdge>): List<RouteChoiceItem> {
    val labels = graph.nodes.associate { it.id to it.label.replace('\n', ' ') }
    val relations = mapOf("control_flow" to "控制流", "call" to "调用", "data_flow" to "数据流")
    return edges.mapIndexed { index, edge ->
        val relation = relations[edge.relation] ?: edge.relation
        val condition = edge.condition?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        RouteChoiceItem(
            edge.id,
            "${index + 1}. ${labels[edge.from] ?: edge.from} → ${labels[edge.to] ?: edge.to}$condition · $relation",
        )
    }
}

/** Defers outside-click cancellation until the graph has processed its candidate click. */
internal class RouteChoiceSelection(private val finished: (String?) -> Unit) : AutoCloseable {
    private var delivered = false
    val isFinished: Boolean get() = delivered

    fun choose(edgeId: String?) {
        if (delivered) return
        delivered = true
        finished(edgeId)
    }

    fun cancelLater() {
        SwingUtilities.invokeLater { choose(null) }
    }

    override fun close() {
        delivered = true
    }
}

/** Uses the IDE's standard list renderer, speed search, hover selection and theme. */
internal val ideRouteChoicePresenter =
    RouteChoicePresenter { graph, direction, edges, component, point, preview, finished ->
        val items = routeChoiceItems(graph, edges)
        val selection = RouteChoiceSelection(finished)
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setFont(UIManager.getFont("Label.font"))
            .setTitle(if (direction == StepDirection.PREVIOUS) "选择上一步路线" else "选择下一步路线")
            .setNamerForFiltering { it.label }
            .setAutoselectOnMouseMove(true)
            .setSelectedValue(items.first(), true)
            .setItemSelectedCallback { preview(it?.edgeId) }
            .setItemChosenCallback { selection.choose(it.edgeId) }
            .setCancelCallback { selection.isFinished || !isGraphCandidateClick(EventQueue.getCurrentEvent() as? MouseEvent) }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!event.isOk) selection.cancelLater()
                }
            })
            .createPopup()
        val placement = RouteChoicePopupPlacement(popup, component, point, selection)
        placement.show()
        placement
    }

/** A graph candidate click must reach the graph before popup dismissal clears its choices. */
internal fun isGraphCandidateClick(event: MouseEvent?): Boolean {
    if (event == null || event.button != MouseEvent.BUTTON1) return false
    val diagram = (event.component as? UnderstandingDiagram)
        ?: SwingUtilities.getAncestorOfClass(UnderstandingDiagram::class.java, event.component) as? UnderstandingDiagram
        ?: return false
    val point = SwingUtilities.convertPoint(event.component, event.point, diagram)
    return diagram.routeNavigation?.hasChoice == true &&
            (diagram.nodeAt(point) in diagram.choiceTargets || diagram.candidateEdgeAt(point) != null)
}
