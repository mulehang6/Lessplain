package com.master.lessplain.diagram

import com.master.lessplain.core.StepDirection
import com.master.lessplain.model.GraphEdge

/** 临时候选强调，与持久化阅读路径及其动画分开。 */
internal class DiagramRouteChoiceState {
    var edgeIds = emptySet<String>()
        private set
    var focusedEdgeId: String? = null
    var focusedNodeId: String? = null

    fun setCandidates(edges: List<GraphEdge>, @Suppress("UNUSED_PARAMETER") direction: StepDirection) {
        edgeIds = edges.mapTo(linkedSetOf()) { it.id }
    }

    fun clear() {
        edgeIds = emptySet()
        focusedEdgeId = null
        focusedNodeId = null
    }
}
