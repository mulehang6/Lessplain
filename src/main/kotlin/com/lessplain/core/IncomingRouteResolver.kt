package com.lessplain.core

import com.lessplain.model.GraphEdge
import com.lessplain.model.TRAVERSABLE_RELATIONS
import com.lessplain.model.UnderstandingGraph

/** 通过索引反向遍历，按可达性筛选选择项，不枚举完整路线。 */
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

    fun outgoing(nodeId: String): List<GraphEdge> = outgoing[nodeId].orEmpty()
    fun incoming(nodeId: String): List<GraphEdge> = incoming[nodeId].orEmpty()

    fun begin(
        target: String,
        remembered: Map<String, String>,
        fixedSuffix: List<String> = emptyList(),
        knownStart: String? = null,
    ): Fill {
        val origins = entries.filterTo(mutableSetOf()) { target in reachable(setOf(it), emptySet()) }
        val fallback = knownStart?.takeIf { target in reachable(setOf(it), emptySet()) }
        return Fill(target, origins.ifEmpty { fallback?.let(::setOf).orEmpty() }, fixedSuffix, remembered)
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

    /** 临时的路径补全事务，只有成功时才记住其中的强制选择。 */
    inner class Fill internal constructor(
        val target: String,
        private val origins: Set<String>,
        fixedSuffix: List<String>,
        private val remembered: Map<String, String>,
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
        val path: List<String> get() = reversed.asReversed().toList()

        fun advance(): Fill {
            choices = emptyList()
            while (true) {
                if (cursor in origins) {
                    complete = true
                    return this
                }
                if (reversed.size >= 100) {
                    reason = "来路超过 100 步，当前显示局部路径"
                    return this
                }
                if (origins.isEmpty()) {
                    reason = "没有可识别的入口，当前从此节点局部探索"
                    return this
                }
                val reachable = reachable(origins, visited)
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
