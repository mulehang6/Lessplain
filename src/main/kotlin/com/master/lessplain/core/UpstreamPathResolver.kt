package com.master.lessplain.core

import com.master.lessplain.model.TRAVERSABLE_RELATIONS
import com.master.lessplain.model.UnderstandingGraph

/** Computes the finite union of entry-to-target walks, stopping on the first arrival at the target. */
internal class UpstreamPathResolver(private val graph: UnderstandingGraph, scopes: Map<String, String> = emptyMap()) {
    private val nodeIds = graph.nodes.map { it.id }.filter { scopes.isEmpty() || it in scopes }.toSet()
    private val edges = graph.edges.filter {
        it.relation in TRAVERSABLE_RELATIONS && it.from in nodeIds && it.to in nodeIds &&
            (scopes.isEmpty() || scopes[it.from] == scopes[it.to])
    }
    private val outgoing = edges.groupBy { it.from }
    private val incoming = edges.groupBy { it.to }
    private val cache = mutableMapOf<String, Set<String>>()

    fun edgeIdsTo(target: String): Set<String> = cache.getOrPut(target) {
        if (target !in nodeIds) return@getOrPut emptySet()
        val ancestors = reach(listOf(target)) { node ->
            incoming[node].orEmpty().filter { it.from != target }.map { it.from }
        }
        val starts = graph.nodes.filter { it.id in ancestors && "start" in it.flowRoles }.map { it.id }
        val roots = starts.ifEmpty { sourceComponents(ancestors) }
        val reachable = reach(roots) { node ->
            if (node == target) emptyList() else outgoing[node].orEmpty().map { it.to }
        }
        edges.filter { it.from != target && it.from in reachable && it.to in ancestors }
            .mapTo(linkedSetOf()) { it.id }
    }

    /** Iterative Kosaraju keeps closed upstream components usable without choosing an arbitrary entry. */
    private fun sourceComponents(ancestors: Set<String>): List<String> {
        val visited = hashSetOf<String>()
        val finished = mutableListOf<String>()
        for (start in ancestors) {
            val stack = ArrayDeque<Pair<String, Boolean>>()
            stack.addLast(start to false)
            while (stack.isNotEmpty()) {
                val (node, exit) = stack.removeLast()
                if (exit) {
                    finished += node
                } else if (visited.add(node)) {
                    stack.addLast(node to true)
                    outgoing[node].orEmpty().forEach { edge ->
                        if (edge.to in ancestors && edge.to !in visited) stack.addLast(edge.to to false)
                    }
                }
            }
        }
        val componentOf = mutableMapOf<String, Int>()
        val components = mutableListOf<MutableList<String>>()
        for (start in finished.asReversed()) {
            if (start in componentOf) continue
            val index = components.size
            val members = mutableListOf<String>()
            components += members
            val stack = ArrayDeque<String>()
            stack.addLast(start)
            componentOf[start] = index
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                members += node
                incoming[node].orEmpty().forEach { edge ->
                    if (edge.from in ancestors && edge.from !in componentOf) {
                        componentOf[edge.from] = index
                        stack.addLast(edge.from)
                    }
                }
            }
        }
        val hasIncoming = hashSetOf<Int>()
        edges.filter { it.from in ancestors && it.to in ancestors }.forEach { edge ->
            if (componentOf[edge.from] != componentOf[edge.to]) hasIncoming += componentOf.getValue(edge.to)
        }
        return components.filterIndexed { index, _ -> index !in hasIncoming }.flatten()
    }

    private fun reach(starts: List<String>, adjacent: (String) -> List<String>): Set<String> {
        val visited = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        starts.forEach { if (visited.add(it)) pending.addLast(it) }
        while (pending.isNotEmpty()) {
            adjacent(pending.removeFirst()).forEach { if (visited.add(it)) pending.addLast(it) }
        }
        return visited
    }
}
