package com.lessplain.core

import com.lessplain.model.GraphEdge
import com.lessplain.model.TRAVERSABLE_RELATIONS
import com.lessplain.model.UnderstandingGraph

/** 在来源记录内计算入口到目标的高亮范围，不枚举路线。 */
class UpstreamRoutes(graph: UnderstandingGraph, scopes: Map<String, String> = emptyMap()) {
    private val nodeIds = graph.nodes.map { it.id }.filter { scopes.isEmpty() || it in scopes }.toSet()
    private val edges = graph.edges.filter {
        it.relation in TRAVERSABLE_RELATIONS && it.from in nodeIds && it.to in nodeIds &&
            (scopes.isEmpty() || scopes[it.from] == scopes[it.to])
    }
    private val outgoing = edges.groupBy { it.from }
    private val incoming = edges.groupBy { it.to }
    val entryNodeIds: List<String> = nodeIds.filter { incoming[it].isNullOrEmpty() }.sorted()
    private val reachableFromEntries = reachable(entryNodeIds) { outgoing[it].orEmpty().map(GraphEdge::to) }

    fun isEntry(nodeId: String): Boolean = nodeId in entryNodeIds

    /** 所有符合条件的边只高亮一次，不设置候选数量或分页限制。 */
    fun edgesTo(targetNodeId: String): Set<String> {
        if (targetNodeId !in nodeIds) return emptySet()
        val ancestors = ancestorsOf(targetNodeId)
        return edges.asSequence()
            .filter { it.from in reachableFromEntries && it.to in ancestors }
            .map { it.id }
            .toSet()
    }

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
}
