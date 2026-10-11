package com.master.lessplain.diagram

import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.GraphEdge

/** 保留所有前向阅读分支，循环内按 Agent 给出的节点顺序识别回向关系。 */
internal fun forwardReadingEdges(graph: UnderstandingGraph): List<GraphEdge> {
    val nodeOrder = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()
    val edges = graph.edges.filter { edge ->
        edge.relation in setOf("control_flow", "call") &&
            edge.from in nodeOrder && edge.to in nodeOrder && edge.from != edge.to
    }
    val outgoing = edges.groupBy { it.from }
    var nextComponent = 0
    var nextIndex = 0
    val indices = mutableMapOf<String, Int>()
    val lowLinks = mutableMapOf<String, Int>()
    val stack = ArrayDeque<String>()
    val onStack = mutableSetOf<String>()
    val component = mutableMapOf<String, Int>()

    fun visit(id: String) {
        val index = nextIndex++
        indices[id] = index
        lowLinks[id] = index
        stack.addLast(id)
        onStack.add(id)
        for (edge in outgoing[id].orEmpty()) {
            val target = edge.to
            if (target !in indices) {
                visit(target)
                lowLinks[id] = minOf(lowLinks.getValue(id), lowLinks.getValue(target))
            } else if (target in onStack) {
                lowLinks[id] = minOf(lowLinks.getValue(id), indices.getValue(target))
            }
        }
        if (lowLinks.getValue(id) == index) {
            while (true) {
                val member = stack.removeLast()
                onStack.remove(member)
                component[member] = nextComponent
                if (member == id) break
            }
            nextComponent++
        }
    }
    graph.nodes.forEach { if (it.id !in indices) visit(it.id) }

    // 循环内保留符合阅读顺序的后续路线，不为回边设置提示。
    return edges.filter { edge ->
        component[edge.from] != component[edge.to] ||
            nodeOrder.getValue(edge.from) < nodeOrder.getValue(edge.to)
    }
}

/** 从每个入口选择稳定的最长后续路线，为 ELK 提供直线提示。 */
internal fun mainlineEdgeIds(graph: UnderstandingGraph): Set<String> {
    val forward = forwardReadingEdges(graph)
    val forwardById = forward.associateBy { it.id }
    val choices = forward.groupBy { it.from }
    val length = mutableMapOf<String, Int>()
    val bestEdge = mutableMapOf<String, String>()
    fun longestFrom(id: String): Int = length.getOrPut(id) {
        var best = 0
        for (edge in choices[id].orEmpty()) {
            val candidate = 1 + longestFrom(edge.to)
            if (candidate > best) {
                best = candidate
                bestEdge[id] = edge.id
            }
        }
        best
    }
    val incoming = forward.mapTo(mutableSetOf()) { it.to }
    val entries = graph.nodes.map { it.id }.filter { it !in incoming }
        .ifEmpty { graph.nodes.firstOrNull()?.let { listOf(it.id) }.orEmpty() }
    val selected = linkedSetOf<String>()
    for (entry in entries) {
        var current = entry
        longestFrom(current)
        while (true) {
            val edgeId = bestEdge[current] ?: break
            if (!selected.add(edgeId)) break
            current = forwardById.getValue(edgeId).to
        }
    }
    return selected
}
