package com.lessplain

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.branchNodeIds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GraphTraversalTest {
    @Test fun `forks count distinct browsable successors and ignore incoming and structural links`() {
        val graph = UnderstandingGraph(
            listOf("a", "b", "merge", "fork", "parallel", "self").map { GraphNode(it, it) },
            listOf(
                GraphEdge("a-merge", "a", "merge", "call"),
                GraphEdge("b-merge", "b", "merge", "data_flow"),
                GraphEdge("merge-fork", "merge", "fork", "control_flow"),
                GraphEdge("fork-a", "fork", "a", "call"),
                GraphEdge("fork-b", "fork", "b", "data_flow"),
                GraphEdge("parallel-yes", "parallel", "a", "control_flow", "yes"),
                GraphEdge("parallel-no", "parallel", "a", "control_flow", "no"),
                GraphEdge("parallel-dependency", "parallel", "b", "dependency"),
                GraphEdge("parallel-structure", "parallel", "fork", "structure"),
                GraphEdge("self-loop", "self", "self", "control_flow", "repeat"),
                GraphEdge("self-end", "self", "a", "control_flow", "end"),
            ),
        )
        assertEquals(setOf("fork", "self"), graph.branchNodeIds())
    }
}
