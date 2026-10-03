package com.master.understanding.diagram

import com.master.understanding.core.CompleteRoute
import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.UpstreamPathResolver
import com.master.understanding.model.RouteNavigationState
import com.master.understanding.core.UpstreamRoutes
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.UnderstandingGraph
import java.awt.Component
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** Owns upstream focus, temporary previews and explicit route confirmation for one graph. */
internal class DiagramCompleteRouteNavigation(
    private val diagram: UnderstandingDiagram,
    val graph: UnderstandingGraph,
) : AutoCloseable {
    var routes = UpstreamRoutes(graph)
        private set
    private var recordScopes = emptyMap<String, String>()
    private var upstream = UpstreamPathResolver(graph)
    var upstreamEdgeIds = emptySet<String>()
        private set
    var previewEdgeIds: Set<String>? = null
        private set
    private val callbacks = mutableSetOf<(CompleteRoute, String) -> Unit>()
    private var picker: DiagramRoutePicker? = null
    var canConfirm: () -> Boolean = { true }

    /** Uses the same record boundaries as the stepping/source-navigation owner. */
    fun configureScopes(scopes: Map<String, String>) {
        closePicker()
        recordScopes = scopes.toMap()
        routes = UpstreamRoutes(graph, recordScopes)
        upstream = UpstreamPathResolver(graph, recordScopes)
        diagram.controls.refreshRouteAvailability()
    }

    /** Keeps the clicked prefix's upstream union separate from the explicitly selected suffix. */
    fun showNavigation(state: RouteNavigationState, nodeId: String, centerNode: Boolean) {
        if (nodeId !in diagram.nodeIds) return
        if (nodeId != diagram.currentNodeId) closePicker()
        upstreamEdgeIds = upstream.edgeIdsTo(state.prefixNodeId)
        diagram.routeEdgeIds = state.selectedEdgeIds.toSet()
        diagram.currentNodeId = nodeId
        diagram.selectedNodeId = nodeId
        if (centerNode && diagram.autoFollowNode) diagram.centerOnNode(nodeId)
        diagram.updateTimer()
        diagram.repaint()
    }

    fun showPath(path: List<String>, nodeId: String, centerNode: Boolean) {
        if (nodeId !in diagram.nodeIds) return
        if (nodeId != diagram.currentNodeId) closePicker()
        val first = path.firstOrNull()?.let { id -> graph.edges.firstOrNull { it.id == id }?.from }
        diagram.routeEdgeIds = if (first != null && routes.isEntry(first)) path.toSet() else emptySet()
        upstreamEdgeIds = if (diagram.routeEdgeIds.isEmpty()) routes.edgesTo(nodeId) else emptySet()
        diagram.currentNodeId = nodeId
        diagram.selectedNodeId = nodeId
        if (centerNode && diagram.autoFollowNode) diagram.centerOnNode(nodeId)
        diagram.updateTimer()
        diagram.repaint()
    }

    fun focus(nodeId: String, notify: Boolean) {
        if (nodeId !in diagram.nodeIds) return
        if (!notify && nodeId == diagram.currentNodeId) return
        closePicker()
        showPath(emptyList(), nodeId, centerNode = true)
        diagram.overlays.rememberTarget(nodeId)
        if (notify) diagram.callbacks.toList().forEach { it(nodeId) }
    }

    fun preview(route: CompleteRoute?) {
        previewEdgeIds = route?.edgeIds?.toSet()
        diagram.updateTimer()
        diagram.repaint()
    }

    fun confirm(route: CompleteRoute, target: String): Boolean {
        if (diagram.disposed || target != diagram.currentNodeId || !canConfirm()) return false
        val entry = route.edgeIds.firstOrNull()?.let { id -> graph.edges.firstOrNull { it.id == id }?.from } ?: target
        if (route.entryNodeId != entry) return false
        if (!StaticExplorer(graph, scopes = recordScopes).selectCompletePath(route.edgeIds, target)) return false
        showPath(route.edgeIds, target, centerNode = false)
        callbacks.toList().forEach { it(route, target) }
        closePicker()
        return true
    }

    fun onSelected(callback: (CompleteRoute, String) -> Unit): AutoCloseable {
        callbacks += callback
        return AutoCloseable { callbacks -= callback }
    }

    fun showPicker(anchor: Component = diagram) {
        val target = diagram.currentNodeId ?: return
        closePicker()
        picker = DiagramRoutePicker(this, target, ::closePicker).also { it.show(anchor) }
    }

    fun showStepChoices(edges: List<GraphEdge>, choose: (String) -> Unit) {
        val labels = graph.nodes.associate { it.id to it.label }
        JPopupMenu().apply {
            edges.forEachIndexed { index, edge ->
                val label = edge.condition?.takeIf(String::isNotBlank) ?: edge.relation
                add(JMenuItem("${index + 1}. $label → ${labels[edge.to] ?: edge.to}").apply {
                    addActionListener { choose(edge.id) }
                })
            }
            show(diagram, 16, (diagram.height - 60).coerceAtLeast(0))
        }
    }

    private fun closePicker() {
        val previous = picker
        picker = null
        previous?.close()
        preview(null)
    }

    override fun close() {
        closePicker()
        callbacks.clear()
        canConfirm = { false }
    }
}

internal fun UnderstandingDiagram.showUnderstandingPath(pathEdgeIds: List<String>, nodeId: String, centerNode: Boolean) =
    completeRouteNavigation.showPath(pathEdgeIds, nodeId, centerNode)

internal fun UnderstandingDiagram.selectUnderstandingNode(nodeId: String) =
    completeRouteNavigation.focus(nodeId, notify = false)

internal fun UnderstandingDiagram.selectUnderstandingNode(nodeId: String, notify: Boolean) =
    completeRouteNavigation.focus(nodeId, notify)

internal fun UnderstandingDiagram.onCompleteRouteSelected(callback: (CompleteRoute, String) -> Unit): AutoCloseable =
    completeRouteNavigation.onSelected(callback)
