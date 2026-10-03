package com.master.understanding.diagram

import com.master.understanding.core.StepDirection
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.UnderstandingGraph
import java.awt.Component
import java.awt.Point
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal fun interface RouteChoicePresenter {
    fun show(
        graph: UnderstandingGraph,
        direction: StepDirection,
        edges: List<GraphEdge>,
        component: Component,
        point: Point,
        finished: (String?) -> Unit,
    ): AutoCloseable
}

/** Each menu item keeps the exact edge ID even when conditions share the same destination. */
internal data class RouteChoiceItem(val edgeId: String, val label: String)

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

/** Defers cancellation on invisibility so item actions can finish after Swing hides the menu. */
internal class RouteChoicePopup(
    graph: UnderstandingGraph,
    direction: StepDirection,
    edges: List<GraphEdge>,
    private val finished: (String?) -> Unit,
) : AutoCloseable {
    private var delivered = false
    val menu = JPopupMenu(if (direction == StepDirection.PREVIOUS) "选择上一步路线" else "选择下一步路线")

    private fun deliver(edgeId: String?) {
        if (delivered) return
        delivered = true
        finished(edgeId)
    }

    init {
        routeChoiceItems(graph, edges).forEach { item ->
            menu.add(JMenuItem(item.label).apply { addActionListener { deliver(item.edgeId) } })
        }
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(event: PopupMenuEvent) = deliver(null)
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                SwingUtilities.invokeLater { deliver(null) }
            }
        })
    }

    fun show(component: Component, point: Point) = menu.show(component, point.x, point.y)

    override fun close() {
        delivered = true
        menu.isVisible = false
    }
}

internal val swingRouteChoicePresenter = RouteChoicePresenter { graph, direction, edges, component, point, finished ->
    RouteChoicePopup(graph, direction, edges, finished).apply { show(component, point) }
}
