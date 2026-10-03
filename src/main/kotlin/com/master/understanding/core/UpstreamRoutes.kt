package com.master.understanding.core

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.TRAVERSABLE_RELATIONS
import com.master.understanding.model.UnderstandingGraph
import java.util.concurrent.CancellationException

/** A complete reading route retains the original edge IDs, including parallel alternatives. */
data class CompleteRoute(val entryNodeId: String, val edgeIds: List<String>)

/**
 * Separates inexpensive upstream highlighting from potentially exponential route enumeration.
 * Entries are defined by incoming browsable edges, independently of business-role labels.
 * Optional record scopes keep routes in a combined follow-up canvas within their source record.
 */
class UpstreamRoutes(graph: UnderstandingGraph, scopes: Map<String, String> = emptyMap()) {
    private val nodeIds = graph.nodes.map { it.id }.filter { scopes.isEmpty() || it in scopes }.toSet()
    private val edges = graph.edges.filter {
        it.relation in TRAVERSABLE_RELATIONS && it.from in nodeIds && it.to in nodeIds &&
            (scopes.isEmpty() || scopes[it.from] == scopes[it.to])
    }
    private val outgoing = edges.groupBy { it.from }.mapValues { (_, values) -> values.sortedBy { it.id } }
    private val incoming = edges.groupBy { it.to }
    val entryNodeIds: List<String> = nodeIds.filter { incoming[it].isNullOrEmpty() }.sorted()
    private val reachableFromEntries = reachable(entryNodeIds) { outgoing[it].orEmpty().map(GraphEdge::to) }

    fun isEntry(nodeId: String): Boolean = nodeId in entryNodeIds

    /** All eligible edges are highlighted once; no candidate count or pagination limit applies. */
    fun edgesTo(targetNodeId: String): Set<String> {
        if (targetNodeId !in nodeIds) return emptySet()
        val ancestors = ancestorsOf(targetNodeId)
        return edges.asSequence()
            .filter { it.from in reachableFromEntries && it.to in ancestors }
            .map { it.id }
            .toSet()
    }

    /**
     * Lazily enumerates simple, complete routes. Cycles remain visible in the graph but do not
     * produce infinitely many candidates. Advance this iterator on a background thread.
     */
    fun routesTo(
        targetNodeId: String,
        entryNodeId: String? = null,
        requiredEdgeId: String? = null,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): Iterator<CompleteRoute> = sequence {
        if (targetNodeId !in nodeIds) return@sequence
        val ancestors = ancestorsOf(targetNodeId)
        val required = requiredEdgeId?.let { id -> edges.firstOrNull { it.id == id } ?: return@sequence }
        val beforeRequired = required?.let { ancestorsOf(it.from) }
        val entries = entryNodeIds.filter { it in ancestors && (entryNodeId == null || it == entryNodeId) &&
            (beforeRequired == null || it in beforeRequired) }
        for (entry in entries) {
            if (entry == targetNodeId) {
                if (requiredEdgeId == null) yield(CompleteRoute(entry, emptyList()))
                continue
            }
            val visited = mutableSetOf(entry)
            val path = mutableListOf<GraphEdge>()
            val stack = ArrayDeque<RouteFrame>()
            fun frame(nodeId: String) = RouteFrame(nodeId, outgoing[nodeId].orEmpty().iterator())
            stack.addLast(frame(entry))
            while (stack.isNotEmpty()) {
                if (cancelled()) throw CancellationException("Route query cancelled")
                val current = stack.last()
                if (!current.edges.hasNext()) {
                    stack.removeLast()
                    visited.remove(current.nodeId)
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                    continue
                }
                val edge = current.edges.next()
                if (edge.to !in ancestors || edge.to in visited) continue
                if (beforeRequired != null && edge.id != requiredEdgeId &&
                    path.none { it.id == requiredEdgeId } && edge.to !in beforeRequired) continue
                path += edge
                if (edge.to == targetNodeId) {
                    if (requiredEdgeId == null || path.any { it.id == requiredEdgeId }) {
                        yield(CompleteRoute(entry, path.map { it.id }))
                    }
                    path.removeAt(path.lastIndex)
                } else {
                    visited += edge.to
                    stack.addLast(frame(edge.to))
                }
            }
        }
    }.iterator()

    private fun ancestorsOf(nodeId: String): Set<String> =
        reachable(listOf(nodeId)) { incoming[it].orEmpty().map(GraphEdge::from) }

    private fun reachable(starts: List<String>, successors: (String) -> List<String>): Set<String> {
        val result = starts.toMutableSet()
        val pending = ArrayDeque(starts)
        while (pending.isNotEmpty()) {
            for (next in successors(pending.removeFirst())) if (result.add(next)) pending.addLast(next)
        }
        return result
    }

    private data class RouteFrame(val nodeId: String, val edges: Iterator<GraphEdge>)
}
