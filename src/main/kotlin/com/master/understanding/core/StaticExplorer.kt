package com.master.understanding.core

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.RouteNavigationState
import com.master.understanding.model.TRAVERSABLE_RELATIONS
import com.master.understanding.model.UnderstandingGraph

/** A reading route has an unresolved upstream prefix and a chosen suffix, without claiming runtime facts. */
class StaticExplorer(
    private val graph: UnderstandingGraph,
    savedEdges: List<String> = emptyList(),
    savedNodeId: String? = null,
    savedNavigationState: RouteNavigationState? = null,
    scopes: Map<String, String> = emptyMap(),
) {
    val routeScopes: Map<String, String> = scopes.toMap()
    private val nodeIds = graph.nodes.map { it.id }.filter { routeScopes.isEmpty() || it in routeScopes }.toSet()
    private val resolver = IncomingRouteResolver(graph, routeScopes)
    private val remembered = mutableMapOf<String, String>()
    private var explicitStart = false
    private var filling: IncomingRouteResolver.Fill? = null
    var routeNotice: String? = null
        private set
    private val edgesById = graph.edges.associateBy { it.id }
    private val traversableEdges = graph.edges.filter {
        it.relation in TRAVERSABLE_RELATIONS && it.from in nodeIds && it.to in nodeIds &&
            (routeScopes.isEmpty() || routeScopes[it.from] == routeScopes[it.to])
    }
    private val upstream = UpstreamPathResolver(graph, routeScopes)
    private val selectedEdges = mutableListOf<String>()
    private var startNodeId = defaultStartNodeId()

    /** Only the ordered, explicitly selected suffix is exposed as staticPath. */
    val path: List<String> get() = selectedEdges.toList()
    val currentNodeId: String
        get() = selectedEdges.lastOrNull()?.let { edgesById[it]?.to } ?: startNodeId
    val navigationState: RouteNavigationState get() = RouteNavigationState(startNodeId, path)
    val highlightedEdgeIds: Set<String> get() = upstream.edgeIdsTo(startNodeId) + selectedEdges
    val canPrevious: Boolean get() = availableEdges(StepDirection.PREVIOUS).isNotEmpty()
    val canNext: Boolean get() = availableEdges().any(::canAppend)
    val canGoBack: Boolean get() = canPrevious
    val isChoosingIncoming: Boolean get() = filling != null
    val canComplete: Boolean get() = !resolver.isEntry(startNodeId) && resolver.incoming(startNodeId).isNotEmpty()

    init {
        restorePath(savedEdges, savedNodeId, savedNavigationState)
    }

    fun availableEdges(direction: StepDirection = StepDirection.NEXT): List<GraphEdge> = traversableEdges.filter {
        if (direction == StepDirection.NEXT) it.from == currentNodeId else it.to == currentNodeId
    }

    /** Direct selection always unfolds every upstream route, including when reselecting the current node. */
    fun jumpTo(nodeId: String): Boolean {
        if (nodeId !in nodeIds) return false
        cancelIncoming()
        routeNotice = null
        selectedEdges.clear()
        startNodeId = nodeId
        explicitStart = true
        return true
    }

    /** Keeps the latest selected visit while synchronizing source or capturing a context. */
    fun synchronizeNode(nodeId: String): Boolean {
        if (nodeId !in nodeIds) return false
        if (nodeId == currentNodeId) return true
        return truncateToVisit(nodeId) || jumpTo(nodeId)
    }

    /** Invalid snapshots retain the selected node and unfold its upstream routes. */
    fun restorePath(
        savedEdges: List<String>,
        savedNodeId: String?,
        savedNavigationState: RouteNavigationState? = null,
    ): Boolean {
        cancelIncoming()
        routeNotice = null
        selectedEdges.clear()
        val nodeId = savedNodeId?.takeIf { it in nodeIds }
        startNodeId = nodeId ?: defaultStartNodeId()
        if (nodeId == null) return savedEdges.isEmpty() && savedNavigationState == null
        val candidate = savedNavigationState?.selectedEdgeIds ?: savedEdges
        val restored = validateSavedPath(candidate, nodeId) ?: return false
        if (savedNavigationState != null && savedNavigationState.prefixNodeId != restored.first) return false
        startNodeId = restored.first
        selectedEdges += restored.second
        restored.second.forEach { id -> edgesById[id]?.let { remembered[it.to] = id } }
        return true
    }

    fun next(): StepResult = step(StepDirection.NEXT)

    fun previous(): StepResult = step(StepDirection.PREVIOUS)

    private fun step(direction: StepDirection): StepResult {
        if (isChoosingIncoming) return StepResult.InvalidChoice
        if (direction == StepDirection.NEXT && availableEdges().isNotEmpty() && !canNext) return StepResult.Limit
        val edges = availableEdges(direction)
        return when (edges.size) {
            0 -> StepResult.End
            1 -> choose(edges.single().id, direction)
            else -> StepResult.Choose(edges)
        }
    }

    fun choose(edgeId: String, direction: StepDirection = StepDirection.NEXT): StepResult {
        val edge = availableEdges(direction).firstOrNull { it.id == edgeId } ?: return StepResult.InvalidChoice
        if (direction == StepDirection.NEXT) {
            if (!canAppend(edge)) return StepResult.Limit
            selectedEdges += edge.id
        } else if (!truncateToVisit(edge.from)) {
            jumpTo(edge.from)
        }
        remembered[edge.to] = edge.id
        explicitStart = true
        return StepResult.Moved(currentNodeId)
    }

    /** A multi-edge predecessor still requires an explicit previous-direction choice. */
    fun back(): Boolean = previous() is StepResult.Moved

    private fun truncateToVisit(nodeId: String): Boolean {
        // Exclude the terminal visit so stepping back through a loop removes one occurrence.
        val visits = listOf(startNodeId) + selectedEdges.map { edgesById.getValue(it).to }
        val index = visits.dropLast(1).indexOfLast { it == nodeId }
        if (index < 0) return false
        selectedEdges.subList(index, selectedEdges.size).clear()
        return true
    }

    private fun defaultStartNodeId(): String = graph.nodes.firstOrNull { it.id in nodeIds && "start" in it.flowRoles }?.id
        ?: graph.nodes.firstOrNull { node -> node.id in nodeIds && traversableEdges.none { it.to == node.id } }?.id
        ?: graph.nodes.firstOrNull { it.id in nodeIds }?.id.orEmpty()

    private fun validateSavedPath(savedEdges: List<String>, savedNodeId: String): Pair<String, List<String>>? {
        if (savedEdges.size > graph.nodes.size + MAX_REPEATED_VISITS) return null
        if (savedEdges.isEmpty()) return savedNodeId to emptyList()
        var first: GraphEdge? = null
        var previous: GraphEdge? = null
        for (edgeId in savedEdges) {
            val edge = edgesById[edgeId]?.takeIf { it in traversableEdges } ?: return null
            if (previous != null && previous.to != edge.from) return null
            if (first == null) first = edge
            previous = edge
        }
        if (previous?.to != savedNodeId) return null
        val start = requireNotNull(first).from
        if (repeatedVisits(start, savedEdges) > MAX_REPEATED_VISITS) return null
        return start to savedEdges.toList()
    }

    /** Legacy explicit inference used by completion flows; ordinary graph clicks use jumpTo. */
    fun selectNode(nodeId: String): Boolean {
        if (nodeId !in nodeIds) return false
        cancelIncoming()
        if (nodeId == currentNodeId || nodeId == startNodeId || selectedEdges.any { edgesById[it]?.to == nodeId }) {
            explicitStart = true
            return synchronizeNode(nodeId)
        }
        val fill = resolver.begin(nodeId, remembered, path, startNodeId.takeIf { explicitStart || path.isNotEmpty() },
            knownStart = startNodeId.takeIf { explicitStart || path.isNotEmpty() }).advance()
        applyFill(fill)
        explicitStart = true
        return true
    }

    /** Explicitly completes a chosen route; its transient choices do not change ordinary stepping. */
    fun completeRoute(reselect: Boolean = false): StepResult {
        cancelIncoming()
        val fill = resolver.begin(currentNodeId, if (reselect) emptyMap() else remembered,
            fixedSuffix = if (reselect) emptyList() else path,
            knownStart = startNodeId.takeIf { !reselect && (explicitStart || path.isNotEmpty()) }).advance()
        filling = fill
        return fillResult(fill)
    }

    fun chooseIncoming(edgeId: String): StepResult {
        val fill = filling ?: return choose(edgeId, StepDirection.PREVIOUS)
        if (!fill.choose(edgeId)) return StepResult.InvalidChoice
        return fillResult(fill)
    }

    fun cancelIncoming() {
        filling = null
    }

    private fun fillResult(fill: IncomingRouteResolver.Fill): StepResult {
        if (fill.choices.isNotEmpty()) return StepResult.Choose(fill.choices)
        filling = null
        if (fill.complete) {
            remembered.putAll(fill.confirmed)
            applyFill(fill)
            return StepResult.Moved(currentNodeId)
        }
        routeNotice = fill.reason
        return StepResult.Limit
    }

    private fun applyFill(fill: IncomingRouteResolver.Fill) {
        restorePath(fill.path, fill.target)
        routeNotice = fill.reason
    }

    /** Direct focus shares the same upstream expansion as a graph click. */
    fun focusNode(nodeId: String): Boolean = jumpTo(nodeId)

    /** Accepts only a simple, scoped complete route, leaving the current snapshot intact on failure. */
    fun selectCompletePath(edgeIds: List<String>, targetNodeId: String): Boolean {
        if (targetNodeId !in nodeIds) return false
        val restored = validateSavedPath(edgeIds, targetNodeId) ?: return false
        if (resolver.incoming(restored.first).isNotEmpty() && !resolver.isEntry(restored.first)) return false
        val visited = mutableSetOf(restored.first)
        if (edgeIds.any { !visited.add(requireNotNull(edgesById[it]).to) }) return false
        restorePath(restored.second, targetNodeId)
        explicitStart = true
        return true
    }

    private fun canAppend(edge: GraphEdge): Boolean =
        repeatedVisits(startNodeId, selectedEdges + edge.id) <= MAX_REPEATED_VISITS

    private fun repeatedVisits(start: String, edgeIds: List<String>): Int {
        val visited = mutableSetOf(start)
        return edgeIds.count { !visited.add(requireNotNull(edgesById[it]).to) }
    }

    private companion object {
        const val MAX_REPEATED_VISITS = 100
    }
}

/** Direction relative to the current node, independent of visit history. */
enum class StepDirection { PREVIOUS, NEXT }

sealed interface StepResult {
    data class Moved(val nodeId: String) : StepResult
    data class Choose(val edges: List<GraphEdge>) : StepResult
    data object End : StepResult
    data object Limit : StepResult
    data object InvalidChoice : StepResult
}
