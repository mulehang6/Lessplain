package com.master.understanding.core

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.TRAVERSABLE_RELATIONS
import com.master.understanding.model.UnderstandingGraph

/** Indexed reverse traversal. Reachability filters choices without enumerating complete routes. */
internal class IncomingRouteResolver(graph: UnderstandingGraph, scopes: Map<String, String>) {
    private val nodes = graph.nodes.associateBy { it.id }
    val edges = graph.edges.filter { edge ->
        edge.relation in TRAVERSABLE_RELATIONS && edge.from in nodes && edge.to in nodes &&
            (scopes.isEmpty() || scopes[edge.from] != null && scopes[edge.from] == scopes[edge.to])
    }.associateBy { it.id }
    private val incoming = edges.values.groupBy { it.to }
    private val outgoing = edges.values.groupBy { it.from }
    private val entries = mutableSetOf<String>()

    init {
        val neighbors = edges.values.flatMap { listOf(it.from to it.to, it.to to it.from) }.groupBy({ it.first }, { it.second })
        val remaining = nodes.keys.toMutableSet()
        while (remaining.isNotEmpty()) {
            val component = flood(setOf(remaining.first()), emptySet()) { neighbors[it].orEmpty() }
            remaining.removeAll(component)
            val marked = component.filter { "start" in nodes.getValue(it).flowRoles }
            entries += marked.ifEmpty { component.filter { incoming[it].isNullOrEmpty() } }
        }
    }

    fun isEntry(nodeId: String): Boolean = nodeId in entries
    fun outgoing(nodeId: String): List<GraphEdge> = outgoing[nodeId].orEmpty()
    fun incoming(nodeId: String): List<GraphEdge> = incoming[nodeId].orEmpty()

    fun begin(
        target: String,
        remembered: Map<String, String>,
        prefix: List<String> = emptyList(),
        prefixStart: String? = null,
        fixedSuffix: List<String> = emptyList(),
        knownStart: String? = null,
    ): Fill {
        val prefixNodes = prefixStart?.let { start -> listOf(start) + prefix.map { edges.getValue(it).to } }.orEmpty()
        val anchorIndex = prefixNodes.indices.reversed().firstOrNull { index ->
            val blocked = prefixNodes.take(index).toSet() - prefixNodes[index]
            target in reachable(setOf(prefixNodes[index]), blocked)
        }
        val anchor = anchorIndex?.let(prefixNodes::get)
        val keptPrefix = anchorIndex?.let { prefix.take(it) }.orEmpty()
        val origins = anchor?.let(::setOf) ?: entries.filterTo(mutableSetOf()) { target in reachable(setOf(it), emptySet()) }
        val fallback = knownStart?.takeIf { target in reachable(setOf(it), emptySet()) }
        return Fill(target, origins.ifEmpty { fallback?.let(::setOf).orEmpty() }, keptPrefix, anchor,
            fixedSuffix, remembered, prefixNodes.take(anchorIndex ?: 0).toSet() - setOfNotNull(anchor))
    }

    fun previousChoices(nodeId: String): List<GraphEdge> {
        if (isEntry(nodeId)) return emptyList()
        val reachable = reachable(entries, setOf(nodeId))
        val candidates = incoming(nodeId).filter { it.from in reachable }
        // A deliberate backward step may explore an isolated loop; auto-fill never adds a lap.
        return candidates.ifEmpty { incoming(nodeId) }
    }

    private fun reachable(origins: Set<String>, blocked: Set<String>): Set<String> =
        flood(origins, blocked) { outgoing(it).map(GraphEdge::to) }

    private fun flood(origins: Set<String>, blocked: Set<String>, neighbors: (String) -> List<String>): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        origins.filter { it !in blocked }.forEach { if (seen.add(it)) queue.addLast(it) }
        while (queue.isNotEmpty()) {
            neighbors(queue.removeFirst()).forEach { if (it !in blocked && seen.add(it)) queue.addLast(it) }
        }
        return seen
    }

    /** A transient completion transaction; its forced choices become memory only on success. */
    inner class Fill internal constructor(
        val target: String,
        private val origins: Set<String>,
        private val prefix: List<String>,
        private val anchor: String?,
        fixedSuffix: List<String>,
        private val remembered: Map<String, String>,
        private val prefixBlocked: Set<String>,
    ) {
        private val reversed = fixedSuffix.asReversed().toMutableList()
        private var cursor = fixedSuffix.firstOrNull()?.let { edges.getValue(it).from } ?: target
        private val visited = (listOf(target) + fixedSuffix.map { edges.getValue(it).from }).toMutableSet()
        val confirmed = mutableMapOf<String, String>()
        var choices: List<GraphEdge> = emptyList()
            private set
        var complete = false
            private set
        var reason: String? = null
            private set
        val path: List<String> get() = (if (complete && anchor != null) prefix else emptyList()) + reversed.asReversed()

        fun advance(): Fill {
            choices = emptyList()
            while (true) {
                if (cursor in origins) {
                    complete = true
                    return this
                }
                if (reversed.size + prefix.size >= 100) {
                    reason = "来路超过 100 步，当前显示局部路径"
                    return this
                }
                if (origins.isEmpty()) {
                    reason = "没有可识别的入口，当前从此节点局部探索"
                    return this
                }
                val reachable = reachable(origins, visited + prefixBlocked)
                val viable = incoming(cursor).filter { it.from in reachable && it.from !in visited }
                if (viable.isEmpty()) {
                    reason = "无法连接入口，当前显示局部路径"
                    return this
                }
                val picked = remembered[cursor]?.let { id -> viable.singleOrNull { it.id == id } }
                    ?: viable.singleOrNull()
                if (picked == null) {
                    choices = viable
                    return this
                }
                append(picked)
            }
        }

        fun choose(edgeId: String): Boolean {
            val edge = choices.singleOrNull { it.id == edgeId } ?: return false
            confirmed[cursor] = edge.id
            append(edge)
            advance()
            return true
        }

        private fun append(edge: GraphEdge) {
            reversed += edge.id
            cursor = edge.from
            visited += cursor
        }
    }
}
