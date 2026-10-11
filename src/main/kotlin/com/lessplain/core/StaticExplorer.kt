package com.lessplain.core

import com.lessplain.model.GraphEdge
import com.lessplain.model.RouteNavigationState
import com.lessplain.model.TRAVERSABLE_RELATIONS
import com.lessplain.model.UnderstandingGraph

/** 阅读路线由待确定的上游前缀和已选择的后缀组成，不代表运行事实。 */
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

    /** staticPath 仅暴露有序且由用户明确选择的后缀。 */
    val path: List<String> get() = selectedEdges.toList()
    val currentNodeId: String
        get() = selectedEdges.lastOrNull()?.let { edgesById[it]?.to } ?: startNodeId
    val navigationState: RouteNavigationState get() = RouteNavigationState(startNodeId, path)
    val highlightedEdgeIds: Set<String> get() = upstream.edgeIdsTo(startNodeId) + selectedEdges
    val canPrevious: Boolean get() = availableEdges(StepDirection.PREVIOUS).isNotEmpty()
    val canNext: Boolean get() = availableEdges().any(::canAppend)
    val canGoBack: Boolean get() = canPrevious
    val isChoosingIncoming: Boolean get() = filling != null
    val canComplete: Boolean get() = availableEdges(StepDirection.PREVIOUS).isNotEmpty()

    init {
        restorePath(savedEdges, savedNodeId, savedNavigationState)
    }

    fun availableEdges(direction: StepDirection = StepDirection.NEXT): List<GraphEdge> = traversableEdges.filter {
        it.from != it.to && (if (direction == StepDirection.NEXT) it.from == currentNodeId else it.to == currentNodeId)
    }

    /** 聚焦新节点展开其上游；再次点击当前节点保留已选择路线。 */
    fun jumpTo(nodeId: String): Boolean {
        if (nodeId !in nodeIds) return false
        if (nodeId == currentNodeId) return true
        cancelIncoming()
        routeNotice = null
        selectedEdges.clear()
        startNodeId = nodeId
        explicitStart = true
        return true
    }

    /** 同步源码或捕获上下文时保留最近一次选中的访问位置。 */
    fun synchronizeNode(nodeId: String): Boolean {
        if (nodeId !in nodeIds) return false
        if (nodeId == currentNodeId) return true
        return truncateToVisit(nodeId) || jumpTo(nodeId)
    }

    /** 快照无效时保留选中节点，并展开其上游路线。 */
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

    /** 存在多条前驱边时，仍需明确选择后退方向。 */
    fun back(): Boolean = previous() is StepResult.Moved

    private fun truncateToVisit(nodeId: String): Boolean {
        // 排除末尾访问位置，使循环中的后退只移除一次出现。
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

    /** 路径补全使用的旧显式推导；普通图节点点击使用 jumpTo。 */
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

    /** 显式补全选定路线；临时选择不改变普通的逐步阅读。 */
    fun completeRoute(reselect: Boolean = true): StepResult {
        cancelIncoming()
        val fill = resolver.begin(currentNodeId, if (reselect) emptyMap() else remembered,
            fixedSuffix = if (reselect) emptyList() else path,
            knownStart = startNodeId.takeIf { !reselect && (explicitStart || path.isNotEmpty()) }).advance()
        filling = fill
        return fillResult(fill)
    }

    /** 点击当前节点入边后固定第一段，再逐处分叉选择上游，始终保留目标焦点。 */
    fun completeViaIncoming(edgeId: String): StepResult {
        val edge = availableEdges(StepDirection.PREVIOUS).firstOrNull { it.id == edgeId }
            ?: return StepResult.InvalidChoice
        cancelIncoming()
        val fill = resolver.begin(currentNodeId, emptyMap(), fixedSuffix = listOf(edge.id),
            knownStart = edge.from).advance()
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

    /** 直接聚焦与图节点点击使用相同的上游展开方式。 */
    fun focusNode(nodeId: String): Boolean = jumpTo(nodeId)

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

/** 相对于当前节点的方向，不受访问历史影响。 */
enum class StepDirection { PREVIOUS, NEXT }

sealed interface StepResult {
    data class Moved(val nodeId: String) : StepResult
    data class Choose(val edges: List<GraphEdge>) : StepResult
    data object End : StepResult
    data object Limit : StepResult
    data object InvalidChoice : StepResult
}
