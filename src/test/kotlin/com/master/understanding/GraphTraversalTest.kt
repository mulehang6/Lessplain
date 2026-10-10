package com.master.understanding

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.branchNodeIds
import com.master.understanding.model.traversableEdgesFrom
import com.master.understanding.mcp.parseGraphPayload
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GraphTraversalTest {
    @Test fun `previously marked merge and unmarked fork use the same current graph rule`() {
        val graph = parseGraphPayload("""{"nodes":[
            {"id":"a","label":"A"},{"id":"b","label":"B"},
            {"id":"merge","label":"汇合","decision":true},
            {"id":"fork","label":"分支","decision":false}],"edges":[
            {"id":"am","from":"a","to":"merge","relation":"call"},
            {"id":"bm","from":"b","to":"merge","relation":"call"},
            {"id":"mf","from":"merge","to":"fork","relation":"call"},
            {"id":"fa","from":"fork","to":"a","relation":"call"},
            {"id":"fb","from":"fork","to":"b","relation":"call"}]}""")
        assertEquals(setOf("fork"), graph.branchNodeIds())
    }

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
        assertEquals(listOf("parallel-yes", "parallel-no", "parallel-dependency", "parallel-structure"),
            graph.traversableEdgesFrom("parallel").map { it.id })
        assertEquals(emptyList(), graph.traversableEdgesFrom("missing"))
    }
}
