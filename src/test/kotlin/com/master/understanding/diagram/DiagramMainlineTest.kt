package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DiagramMainlineTest {
    @Test
    fun `chooses longest continuation across a branch and ignores a return edge`() {
        val graph = UnderstandingGraph(
            (0..7).map { GraphNode("n$it", "步骤 $it") },
            listOf(
                GraphEdge("start", "n0", "n1", "control_flow"),
                GraphEdge("short", "n1", "n2", "control_flow"),
                GraphEdge("short-end", "n2", "n7", "control_flow"),
                GraphEdge("long", "n1", "n3", "control_flow"),
                GraphEdge("next", "n3", "n4", "call"),
                GraphEdge("again", "n4", "n5", "control_flow"),
                GraphEdge("finish", "n5", "n7", "control_flow"),
                GraphEdge("return", "n5", "n3", "control_flow"),
                GraphEdge("reference", "n1", "n6", "dependency"),
            ),
        )
        assertEquals(setOf("start", "long", "next", "again", "finish"), mainlineEdgeIds(graph))
    }

    @Test
    fun `equal continuations follow original edge order`() {
        val graph = UnderstandingGraph(
            (0..3).map { GraphNode("n$it", "步骤 $it") },
            listOf(
                GraphEdge("first", "n0", "n2", "control_flow"),
                GraphEdge("second", "n0", "n1", "control_flow"),
                GraphEdge("a", "n2", "n3", "control_flow"),
                GraphEdge("b", "n1", "n3", "control_flow"),
            ),
        )
        assertEquals(setOf("first", "a"), mainlineEdgeIds(graph))
    }
}
