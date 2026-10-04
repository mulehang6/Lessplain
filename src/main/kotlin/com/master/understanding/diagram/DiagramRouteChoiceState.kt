package com.master.understanding.diagram

import com.master.understanding.core.StepDirection
import com.master.understanding.model.GraphEdge

/** Transient candidate emphasis, separate from persisted and animated reading paths. */
internal class DiagramRouteChoiceState {
    var edgeIds = emptySet<String>()
        private set
    var focusedEdgeId: String? = null
    var focusedNodeId: String? = null

    fun setCandidates(edges: List<GraphEdge>, direction: StepDirection) {
        edgeIds = edges.groupBy { if (direction == StepDirection.NEXT) it.to else it.from }
            .values.filter { it.size > 1 }.flatten().mapTo(linkedSetOf()) { it.id }
    }

    fun clear() {
        edgeIds = emptySet()
        focusedEdgeId = null
        focusedNodeId = null
    }
}
