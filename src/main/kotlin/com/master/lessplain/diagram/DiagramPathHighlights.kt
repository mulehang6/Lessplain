package com.master.lessplain.diagram

import com.master.lessplain.core.UpstreamPathResolver
import com.master.lessplain.core.UpstreamRoutes
import com.master.lessplain.model.RouteNavigationState
import com.master.lessplain.model.UnderstandingGraph

/** 使上游聚焦和已选路径高亮与图导航保持同步。 */
internal class DiagramPathHighlights(
    private val diagram: UnderstandingDiagram,
    private val graph: UnderstandingGraph,
) {
    private var routes = UpstreamRoutes(graph)
    private var upstream = UpstreamPathResolver(graph)
    var upstreamEdgeIds = emptySet<String>()
        private set

    /** 与逐步阅读和源码导航使用相同的记录边界。 */
    fun configureScopes(scopes: Map<String, String>) {
        routes = UpstreamRoutes(graph, scopes)
        upstream = UpstreamPathResolver(graph, scopes)
    }

    /** 浏览状态分别保存点击前缀的上游并集和已选择的后缀。 */
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
        diagram.overlays.rememberTarget(nodeId)
        if (notify && diagram.routeNavigation != null) diagram.callbacks.toList().forEach { it(nodeId) }
        else {
            showPath(emptyList(), nodeId, centerNode = true)
            if (notify) diagram.callbacks.toList().forEach { it(nodeId) }
        }
    }
}

internal fun UnderstandingDiagram.showUnderstandingPath(pathEdgeIds: List<String>, nodeId: String, centerNode: Boolean) =
    pathHighlights.showPath(pathEdgeIds, nodeId, centerNode)

internal fun UnderstandingDiagram.selectUnderstandingNode(nodeId: String) =
    pathHighlights.focus(nodeId, notify = false)

internal fun UnderstandingDiagram.selectUnderstandingNode(nodeId: String, notify: Boolean) =
    pathHighlights.focus(nodeId, notify)
