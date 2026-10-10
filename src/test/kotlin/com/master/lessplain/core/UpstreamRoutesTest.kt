package com.master.lessplain.core

import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpstreamRoutesTest {
    @Test
    fun `all upstream routes include both entries and parallel edges but exclude unrelated cycles`() {
        val graph = graph(
            listOf("a", "b", "merge", "target", "other", "cycle"),
            edge("a-m", "a", "merge"), edge("b-m", "b", "merge", "call"),
            edge("m-t", "merge", "target"), edge("m-t-alt", "merge", "target", "data_flow"),
            edge("a-other", "a", "other"), edge("cycle-t", "cycle", "target"),
            edge("cycle", "cycle", "cycle"), edge("structural", "other", "target", "structure"),
        ).let { it.copy(nodes = it.nodes.map { node -> if (node.id == "merge") node.copy(flowRoles = listOf("start")) else node }) }
        val routes = UpstreamRoutes(graph)
        assertEquals(listOf("a", "b"), routes.entryNodeIds)
        assertFalse(routes.isEntry("merge"))
        assertEquals(setOf("a-m", "b-m", "m-t", "m-t-alt", "a-other", "structural"), routes.edgesTo("target"))
    }

    @Test
    fun `reachable cycles are highlighted while entryless cycles do not invent an entry`() {
        val graph = graph(
            listOf("entry", "a", "b", "target"), edge("ea", "entry", "a"),
            edge("ab", "a", "b"), edge("ba", "b", "a"), edge("bt", "b", "target"),
        )
        val routes = UpstreamRoutes(graph)
        assertEquals(setOf("ea", "ab", "ba", "bt"), routes.edgesTo("target"))
        val cyclic = UpstreamRoutes(graph(listOf("a", "b"), edge("ab", "a", "b"), edge("ba", "b", "a")))
        assertTrue(cyclic.edgesTo("b").isEmpty())
    }

    @Test
    fun `parallel alternatives do not limit upstream highlighting`() {
        val nodes = (0..25).map { GraphNode("n$it", "N$it") }
        val edges = (0 until 25).flatMap { index ->
            listOf(edge("$index-a", "n$index", "n${index + 1}"), edge("$index-b", "n$index", "n${index + 1}"))
        }
        val routes = UpstreamRoutes(UnderstandingGraph(nodes, edges))
        assertEquals(50, routes.edgesTo("n25").size)
    }

    @Test
    fun `paths longer than one hundred steps can be restored and browsed`() {
        val graph = UnderstandingGraph(
            (0..250).map { GraphNode("n$it", "N$it") },
            (0 until 250).map { edge("e$it", "n$it", "n${it + 1}") },
        )
        val path = graph.edges.map { it.id }
        val explorer = StaticExplorer(graph, path, "n250")
        assertEquals(path, explorer.path)
        assertTrue(explorer.focusNode("n100"))
        assertTrue(explorer.path.isEmpty())
        val stepped = StaticExplorer(graph)
        repeat(250) { assertTrue(stepped.next() is StepResult.Moved) }
        assertEquals(StepResult.End, stepped.next())
    }

    private fun graph(nodes: List<String>, vararg edges: GraphEdge) =
        UnderstandingGraph(nodes.map { GraphNode(it, it) }, edges.toList())

    private fun edge(id: String, from: String, to: String, relation: String = "control_flow") =
        GraphEdge(id, from, to, relation)
}
