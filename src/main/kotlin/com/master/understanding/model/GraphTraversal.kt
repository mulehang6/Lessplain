package com.master.understanding.model

/** Reading routes include calls and data flow, but never structural or dependency-only links. */
internal fun UnderstandingGraph.traversableEdgesFrom(nodeId: String): List<GraphEdge> =
    edges.filter { it.from == nodeId && it.relation in TRAVERSABLE_RELATIONS }

/** A visual fork represents different reachable successors, independent of incoming degree or source syntax. */
internal fun UnderstandingGraph.branchNodeIds(): Set<String> = edges.asSequence()
    .filter { it.relation in TRAVERSABLE_RELATIONS }
    .groupBy { it.from }
    .filterValues { outgoing -> outgoing.map { it.to }.distinct().size > 1 }
    .keys

internal val TRAVERSABLE_RELATIONS = setOf("control_flow", "call", "data_flow")
