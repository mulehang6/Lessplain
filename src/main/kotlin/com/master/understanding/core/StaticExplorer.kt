package com.master.understanding.core

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.TRAVERSABLE_RELATIONS
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.traversableEdgesFrom

/** A selected route is only a reading choice; it never claims runtime execution. */
class StaticExplorer(
    private val graph: UnderstandingGraph,
    savedEdges: List<String> = emptyList(),
    savedNodeId: String? = null,
) {
    private val edgesById = graph.edges.associateBy { it.id }
    private val selectedEdges = mutableListOf<String>()
    private var startNodeId = defaultStartNodeId()

    val path: List<String> get() = selectedEdges.toList()
    val currentNodeId: String
        get() = selectedEdges.lastOrNull()?.let { edgesById[it]?.to } ?: startNodeId

    init {
        restorePath(savedEdges, savedNodeId)
    }

    fun availableEdges(): List<GraphEdge> = graph.traversableEdgesFrom(currentNodeId)

    /**
     * Synchronizes stepping with a node picked directly on the graph. A node already on the
     * selected route truncates the route to its latest visit; an off-route node starts a new one.
     */
    fun jumpTo(nodeId: String): Boolean {
        if (graph.nodes.none { it.id == nodeId }) return false
        val latestVisit = selectedEdges.indexOfLast { edgeId -> edgesById[edgeId]?.to == nodeId }
        when {
            latestVisit >= 0 -> selectedEdges.subList(latestVisit + 1, selectedEdges.size).clear()
            nodeId == startNodeId -> selectedEdges.clear()
            else -> {
                selectedEdges.clear()
                startNodeId = nodeId
            }
        }
        return true
    }

    /** Restores a shared route snapshot, falling back to its selected node if the path is stale. */
    fun restorePath(savedEdges: List<String>, savedNodeId: String?): Boolean {
        selectedEdges.clear()
        val nodeId = savedNodeId?.takeIf { candidate -> graph.nodes.any { it.id == candidate } }
        if (nodeId == null) {
            startNodeId = defaultStartNodeId()
            return savedEdges.isEmpty()
        }

        val restored = validateSavedPath(savedEdges, nodeId)
        if (restored == null) {
            startNodeId = nodeId
            return false
        }
        startNodeId = restored.first
        selectedEdges += restored.second
        return true
    }

    fun next(): StepResult {
        if (selectedEdges.size >= MAX_STEPS) return StepResult.Limit
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
        if (selectedEdges.size >= MAX_STEPS) return StepResult.Limit
        selectedEdges += edge.id
        return StepResult.Moved(currentNodeId)
    }

    fun back(): Boolean {
        if (selectedEdges.isEmpty()) return false
        selectedEdges.removeAt(selectedEdges.lastIndex)
        return true
    }

    private fun defaultStartNodeId(): String = graph.nodes.firstOrNull { node ->
        graph.edges.none { edge -> edge.to == node.id && edge.relation in TRAVERSABLE_RELATIONS }
    }?.id ?: graph.nodes.firstOrNull()?.id.orEmpty()

    private fun validateSavedPath(savedEdges: List<String>, savedNodeId: String): Pair<String, List<String>>? {
        if (savedEdges.size > MAX_STEPS) return null
        if (savedEdges.isEmpty()) return savedNodeId to emptyList()

        var first: GraphEdge? = null
        var previous: GraphEdge? = null
        for (edgeId in savedEdges) {
            val edge = edgesById[edgeId]?.takeIf { it.relation in TRAVERSABLE_RELATIONS } ?: return null
            if (previous != null && previous.to != edge.from) return null
            if (first == null) first = edge
            previous = edge
        }
        if (previous?.to != savedNodeId) return null
        return requireNotNull(first).from to savedEdges.toList()
    }

    private companion object {
        const val MAX_STEPS = 100
    }
}

sealed interface StepResult {
    data class Moved(val nodeId: String) : StepResult
    data class Choose(val edges: List<GraphEdge>) : StepResult
    data object End : StepResult
    data object Limit : StepResult
    data object InvalidChoice : StepResult
}
