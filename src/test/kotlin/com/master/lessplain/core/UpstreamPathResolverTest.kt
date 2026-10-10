package com.master.lessplain.core

import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals

class UpstreamPathResolverTest {
    @Test
    fun `all entries and both sides of a merge end at the clicked node`() {
        val graph = graph(
            "a", "z", "b", "c", "d", "e", edges = listOf(
                edge("ab", "a", "b"), edge("zc", "z", "c"),
                edge("bd", "b", "d"), edge("cd", "c", "d"), edge("de", "d", "e"),
            )
        )
        assertEquals(setOf("ab", "zc", "bd", "cd"), UpstreamPathResolver(graph).edgeIdsTo("d"))
    }

    @Test
    fun `marked starts take precedence but unrelated marks do not suppress a local entry`() {
        val graph = graph(
            "before", "a", "z", "d", "local", "target", edges = listOf(
                edge("before-a", "before", "a"), edge("ad", "a", "d"), edge("zd", "z", "d"),
                edge("local-target", "local", "target"),
            )
        ).let {
            it.copy(nodes = it.nodes.map { node ->
                if (node.id in setOf("a", "z")) node.copy(flowRoles = listOf("start")) else node
            })
        }
        val resolver = UpstreamPathResolver(graph)
        assertEquals(setOf("ad", "zd"), resolver.edgeIdsTo("d"))
        assertEquals(setOf("local-target"), resolver.edgeIdsTo("target"))
    }

    @Test
    fun `upstream cycles are included but outgoing edges from the target are not`() {
        val graph = graph(
            "a", "b", "c", "d", "after", edges = listOf(
                edge("ab", "a", "b"), edge("bc", "b", "c"), edge("cb", "c", "b"),
                edge("bd", "b", "d"), edge("d-after", "d", "after"), edge("after-d", "after", "d"),
                edge("dd", "d", "d"),
            )
        )
        assertEquals(setOf("ab", "bc", "cb", "bd"), UpstreamPathResolver(graph).edgeIdsTo("d"))
    }

    @Test
    fun `rootless cycle unfolds until first arrival at target`() {
        val graph = graph(
            "a", "b", "c", edges = listOf(
                edge("ab", "a", "b"), edge("bc", "b", "c"), edge("ca", "c", "a"),
            )
        )
        assertEquals(setOf("ab", "bc"), UpstreamPathResolver(graph).edgeIdsTo("c"))
    }

    @Test
    fun `closed source components and ordinary entries are both included`() {
        val graph = graph(
            "a", "b", "z", "d", edges = listOf(
                edge("ab", "a", "b"), edge("ba", "b", "a"), edge("bd", "b", "d"), edge("zd", "z", "d"),
            )
        )
        assertEquals(setOf("ab", "ba", "bd", "zd"), UpstreamPathResolver(graph).edgeIdsTo("d"))
    }

    @Test
    fun `dependencies and structural links supply readable entries and upstream edges`() {
        val graph = graph(
            "a", "b", "d", "x", edges = listOf(
                edge("ab", "a", "b", "call"), edge("bd", "b", "d", "data_flow"),
                edge("xa", "x", "a", "structure"), edge("xd", "x", "d", "dependency"),
            )
        )
        assertEquals(setOf("xa", "ab", "bd", "xd"), UpstreamPathResolver(graph).edgeIdsTo("d"))
        assertEquals(emptySet(), UpstreamPathResolver(graph).edgeIdsTo("missing"))
    }

    @Test
    @Timeout(5)
    fun `exponentially many routes are represented by their finite edge union`() {
        val nodes = (0..60).flatMap { level -> listOf(GraphNode("${level}a", "A"), GraphNode("${level}b", "B")) }
        val edges = (0 until 60).flatMap { level ->
            listOf("a", "b").flatMap { from ->
                listOf("a", "b").map { to -> edge("$level$from$to", "$level$from", "${level + 1}$to") }
            }
        }
        val result = UpstreamPathResolver(UnderstandingGraph(nodes, edges)).edgeIdsTo("60a")
        assertEquals(238, result.size)
    }

    private fun graph(vararg ids: String, edges: List<GraphEdge>) =
        UnderstandingGraph(ids.map { GraphNode(it, it) }, edges)

    private fun edge(id: String, from: String, to: String, relation: String = "control_flow") =
        GraphEdge(id, from, to, relation)
}
