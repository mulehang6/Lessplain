package com.master.lessplain.model

/** 所有支持的关系都可双向阅读，不表示执行顺序。 */
internal fun UnderstandingGraph.traversableEdgesFrom(nodeId: String): List<GraphEdge> =
    edges.filter { it.from == nodeId && it.relation in TRAVERSABLE_RELATIONS }

/** 可视分叉表示多个不同的可达后继，不依据入度或源码语法。 */
internal fun UnderstandingGraph.branchNodeIds(): Set<String> = edges.asSequence()
    .filter { it.relation in BRANCH_RELATIONS }
    .groupBy { it.from }
    .filterValues { outgoing -> outgoing.map { it.to }.distinct().size > 1 }
    .keys

/** 结构和依赖后继可阅读，但不表示流程分叉。 */
internal val TRAVERSABLE_RELATIONS = setOf("control_flow", "call", "data_flow", "structure", "dependency")
private val BRANCH_RELATIONS = setOf("control_flow", "call", "data_flow")
