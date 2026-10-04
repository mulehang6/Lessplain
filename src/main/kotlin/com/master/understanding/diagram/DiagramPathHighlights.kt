package com.master.understanding.diagram

import com.master.understanding.core.UpstreamPathResolver
import com.master.understanding.core.UpstreamRoutes
import com.master.understanding.model.RouteNavigationState
import com.master.understanding.model.UnderstandingGraph

/** Keeps upstream focus and selected path highlights in sync with graph navigation. */
internal class DiagramPathHighlights(
    private val diagram: UnderstandingDiagram,
    private val graph: UnderstandingGraph,
) {
    private var routes = UpstreamRoutes(graph)
    private var upstream = UpstreamPathResolver(graph)
    var upstreamEdgeIds = emptySet<String>()
        private set

    /** Uses the same record boundaries as the stepping/source-navigation owner. */
    fun configureScopes(scopes: Map<String, String>) {
        routes = UpstreamRoutes(graph, scopes)
        upstream = UpstreamPathResolver(graph, scopes)
    }

    /** Keeps the clicked prefix's upstream union separate from the selected suffix in browse state. */
    fun showNavigation(state: RouteNavigationState, nodeId: String, centerNode: Boolean) {
        if (nodeId !in diagram.nodeIds) return
        upstreamEdgeIds = upstream.edgeIdsTo(state.prefixNodeId)
        diagram.routeEdgeIds = state.selectedEdgeIds.toSet()
        select(nodeId, centerNode)
    }

    fun showPath(path: List<String>, nodeId: String, centerNode: Boolean) {
        if (nodeId !in diagram.nodeIds) return
        val first = path.firstOrNull()?.let { id -> graph.edges.firstOrNull { it.id == id }?.from }
        diagram.routeEdgeIds = if (first != null && routes.isEntry(first)) path.toSet() else emptySet()
        upstreamEdgeIds = if (diagram.routeEdgeIds.isEmpty()) routes.edgesTo(nodeId) else emptySet()
        select(nodeId, centerNode)
    }

    private fun select(nodeId: String, centerNode: Boolean) {
        diagram.currentNodeId = nodeId
        diagram.selectedNodeId = nodeId
        if (centerNode && diagram.autoFollowNode) diagram.centerOnNode(nodeId)
        diagram.updateTimer()
        diagram.repaint()
    }

    fun focus(nodeId: String, notify: Boolean) {
        if (nodeId !in diagram.nodeIds) return
        if (notify && diagram.routeNavigation?.selectCandidate(nodeId) == true) return
        if (!notify && nodeId == diagram.currentNodeId) return
        showPath(emptyList(), nodeId, centerNode = true)
        diagram.overlays.rememberTarget(nodeId)
        if (notify) diagram.callbacks.toList().forEach { it(nodeId) }
    }
}

internal fun UnderstandingDiagram.showUnderstandingPath(pathEdgeIds: List<String>, nodeId: String, centerNode: Boolean) =
    pathHighlights.showPath(pathEdgeIds, nodeId, centerNode)

internal fun UnderstandingDiagram.selectUnderstandingNode(nodeId: String) =
    pathHighlights.focus(nodeId, notify = false)

internal fun UnderstandingDiagram.selectUnderstandingNode(nodeId: String, notify: Boolean) =
    pathHighlights.focus(nodeId, notify)
