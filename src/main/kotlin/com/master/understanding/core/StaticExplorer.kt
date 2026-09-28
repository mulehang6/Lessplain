package com.master.understanding.core

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.UnderstandingGraph

/** A selected route is only a reading choice; it never claims runtime execution. */
class StaticExplorer(
    private val graph: UnderstandingGraph,
    savedEdges: List<String> = emptyList(),
    savedNodeId: String? = null,
) {
    private val traversableRelations = setOf("control_flow", "call", "data_flow")
    private val selectedEdges = mutableListOf<String>()
    private var startNodeId = graph.nodes.firstOrNull { node ->
        graph.edges.none { it.to == node.id && it.relation in traversableRelations }
    }?.id ?: graph.nodes.first().id
    val path: List<String> get() = selectedEdges.toList()
    val currentNodeId: String
        get() = selectedEdges.lastOrNull()?.let { edgeId -> graph.edges.first { it.id == edgeId }.to }
            ?: startNodeId

    init {
        savedEdges.firstOrNull()?.let { first ->
            graph.edges.firstOrNull { it.id == first && it.relation in traversableRelations }
                ?.let { startNodeId = it.from }
        }
        for (edgeId in savedEdges) {
            if (availableEdges().none { it.id == edgeId }) break
            selectedEdges += edgeId
        }
        if (savedNodeId != null && savedNodeId != currentNodeId) jumpTo(savedNodeId)
    }

    fun availableEdges(): List<GraphEdge> = graph.edges.filter {
        it.relation in traversableRelations && it.from == currentNodeId
    }

    /** Synchronizes stepping with a node picked directly on the graph. */
    fun jumpTo(nodeId: String): Boolean {
        if (graph.nodes.none { it.id == nodeId }) return false
        val route = routeFromRoot(nodeId)
        selectedEdges.clear()
        startNodeId = route?.first ?: nodeId
        if (route != null) selectedEdges += route.second
        return true
    }

    private fun routeFromRoot(target: String): Pair<String, List<String>>? {
        val connected = mutableSetOf(target)
        val queue = ArrayDeque<String>()
        queue.add(target)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            graph.edges.filter { it.relation in traversableRelations && (it.from == current || it.to == current) }
                .forEach { edge ->
                    val other = if (edge.from == current) edge.to else edge.from
                    if (connected.add(other)) queue.add(other)
                }
        }
        val roots = graph.nodes.filter { it.id in connected && graph.edges.none { edge ->
            edge.to == it.id && edge.from in connected && edge.relation in traversableRelations
        } }.map { it.id }
        val root = (roots + graph.nodes.filter { it.id in connected }.map { it.id }).firstOrNull()
            ?: return null
        if (target == root) return root to emptyList()
        val routes = mutableMapOf(root to emptyList<String>())
        queue.clear()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            graph.edges.filter { it.from == from && it.relation in traversableRelations }.forEach { edge ->
                if (edge.to !in routes) {
                    val route = routes.getValue(from) + edge.id
                    if (edge.to == target) return root to route
                    routes[edge.to] = route
                    queue.add(edge.to)
                }
            }
        }
        return null
    }

    fun next(): StepResult {
        if (selectedEdges.size >= 100) return StepResult.Limit
        val outgoing = availableEdges()
        return when (outgoing.size) {
            0 -> StepResult.End
            1 -> {
                selectedEdges += outgoing.single().id
                StepResult.Moved(currentNodeId)
            }
            else -> StepResult.Choose(outgoing)
        }
    }

    fun choose(edgeId: String): StepResult {
        val edge = availableEdges().firstOrNull { it.id == edgeId } ?: return StepResult.InvalidChoice
        if (selectedEdges.size >= 100) return StepResult.Limit
        selectedEdges += edge.id
        return StepResult.Moved(currentNodeId)
    }

    fun back(): Boolean {
        if (selectedEdges.isEmpty()) return false
        selectedEdges.removeAt(selectedEdges.lastIndex)
        return true
    }
}

sealed interface StepResult {
    data class Moved(val nodeId: String) : StepResult
    data class Choose(val edges: List<GraphEdge>) : StepResult
    data object End : StepResult
    data object Limit : StepResult
    data object InvalidChoice : StepResult
}
