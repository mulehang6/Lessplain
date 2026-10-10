package com.master.understanding.model

/** Every supported relation can be read in either direction without claiming execution order. */
internal fun UnderstandingGraph.traversableEdgesFrom(nodeId: String): List<GraphEdge> =
    edges.filter { it.from == nodeId && it.relation in TRAVERSABLE_RELATIONS }

/** A visual fork represents different reachable successors, independent of incoming degree or source syntax. */
internal fun UnderstandingGraph.branchNodeIds(): Set<String> = edges.asSequence()
    .filter { it.relation in BRANCH_RELATIONS }
    .groupBy { it.from }
    .filterValues { outgoing -> outgoing.map { it.to }.distinct().size > 1 }
    .keys

/** Structural and dependency successors are readable, but do not imply a flow fork. */
internal val TRAVERSABLE_RELATIONS = setOf("control_flow", "call", "data_flow", "structure", "dependency")
private val BRANCH_RELATIONS = setOf("control_flow", "call", "data_flow")
