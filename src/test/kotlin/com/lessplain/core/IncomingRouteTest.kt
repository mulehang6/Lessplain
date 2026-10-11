package com.lessplain.core

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncomingRouteTest {
    private fun graph(vararg edges: Pair<String, String>, starts: Set<String> = emptySet()): UnderstandingGraph {
        val ids = edges.flatMap { listOf(it.first, it.second) }.distinct()
        return UnderstandingGraph(ids.map { GraphNode(it, it, flowRoles = if (it in starts) listOf("start") else emptyList()) },
            edges.mapIndexed { i, (from, to) -> GraphEdge("e$i", from, to, "control_flow", "条件$i") })
    }

    @Test
    fun `direct click fills unique route and preserves latest path visit`() {
        val explorer = StaticExplorer(graph("a" to "b", "b" to "c"))
        assertTrue(explorer.selectNode("c"))
        assertEquals(listOf("e0", "e1"), explorer.path)
        assertTrue(explorer.canComplete)
        assertTrue(explorer.selectNode("b"))
        assertEquals(listOf("e0"), explorer.path)
    }

    @Test
    fun `ambiguous click commits suffix immediately and completion leaves target unchanged until ready`() {
        val explorer = StaticExplorer(graph("email" to "lock", "wx" to "lock", "lock" to "save"))
        explorer.selectNode("save")
        assertEquals("save", explorer.currentNodeId)
        assertEquals(listOf("e2"), explorer.path)
        assertTrue(explorer.canComplete)
        assertEquals(setOf("e0", "e1"), (explorer.completeRoute() as StepResult.Choose).edges.map { it.id }.toSet())
        assertEquals("save", explorer.currentNodeId)
        assertEquals(listOf("e2"), explorer.path)
        assertEquals(StepResult.Moved("save"), explorer.chooseIncoming("e0"))
        assertEquals(listOf("e0", "e2"), explorer.path)
    }

    @Test
    fun `saved initial node without route does not silently select the first of several entries`() {
        val explorer = StaticExplorer(graph("email" to "lock", "wx" to "lock", "lock" to "save"), savedNodeId = "email")
        explorer.selectNode("save")
        assertEquals(listOf("e2"), explorer.path)
        assertEquals(2, (explorer.completeRoute() as StepResult.Choose).edges.size)
        explorer.cancelIncoming()
        explorer.selectNode("email")
        explorer.selectNode("save")
        assertEquals(listOf("e0", "e2"), explorer.path)
    }

    @Test
    fun `cancel does not store preview choices and reselect replaces remembered route only on success`() {
        val explorer = StaticExplorer(graph("a" to "merge", "b" to "merge", "merge" to "tail",
            "c" to "final", "tail" to "final"))
        explorer.selectNode("final")
        explorer.completeRoute()
        assertTrue(explorer.chooseIncoming("e4") is StepResult.Choose)
        explorer.cancelIncoming()
        assertEquals(emptyList(), explorer.path)
        assertEquals(2, (explorer.completeRoute() as StepResult.Choose).edges.size)
        explorer.chooseIncoming("e4")
        explorer.chooseIncoming("e0")
        assertEquals(listOf("e0", "e2", "e4"), explorer.path)
        explorer.selectNode("b")
        explorer.selectNode("final")
        // 新的显式分支在汇合处覆盖之前记住的选择。
        assertEquals(listOf("e1", "e2", "e4"), explorer.path)
        explorer.completeRoute(reselect = true)
        explorer.cancelIncoming()
        assertEquals(listOf("e1", "e2", "e4"), explorer.path)
    }

    @Test
    fun `confirmed choice is reused after visiting unrelated entry`() {
        val g = graph("a" to "merge", "b" to "merge", "merge" to "tail", "x" to "y")
        val explorer = StaticExplorer(g)
        explorer.selectNode("tail")
        explorer.completeRoute()
        explorer.chooseIncoming("e1")
        explorer.selectNode("y")
        explorer.selectNode("tail")
        assertEquals(listOf("e1", "e2"), explorer.path)
    }

    @Test
    fun `back at partial boundary chooses predecessor and never takes first parallel edge`() {
        val explorer = StaticExplorer(graph("a" to "b", "a" to "b", "b" to "c"))
        explorer.selectNode("c")
        assertEquals(StepResult.Moved("b"), explorer.previous())
        val choices = explorer.previous() as StepResult.Choose
        assertEquals(setOf("e0", "e1"), choices.edges.map { it.id }.toSet())
        assertEquals(StepResult.Moved("a"), explorer.chooseIncoming("e1"))
        explorer.selectNode("c")
        assertEquals(listOf("e1", "e2"), explorer.path)
    }

    @Test
    fun `business entry may have incoming loop and automatic fill does not add laps`() {
        val explorer = StaticExplorer(graph("a" to "b", "b" to "c", "c" to "b", starts = setOf("a")))
        explorer.selectNode("c")
        assertEquals(listOf("e0", "e1"), explorer.path)
        explorer.next()
        explorer.choose("e1")
        assertEquals(listOf("e0", "e1", "e2", "e1"), explorer.path)
        explorer.completeRoute()
        assertEquals(listOf("e0", "e1"), explorer.path)
        explorer.selectNode("b")
        assertEquals(listOf("e0"), explorer.path)
    }

    @Test
    fun `isolated cycle is local and explicit stepping still cycles`() {
        val explorer = StaticExplorer(graph("a" to "b", "b" to "a"))
        explorer.selectNode("b")
        assertEquals(emptyList(), explorer.path)
        assertTrue(explorer.routeNotice != null)
        assertEquals(StepResult.Moved("a"), explorer.next())
        assertEquals(StepResult.Moved("b"), explorer.next())
        assertEquals(listOf("e1", "e0"), explorer.path)
    }

    @Test
    fun `record scopes exclude cross record edges and non browsable relations`() {
        val g = graph("a" to "b", "b" to "c").let { it.copy(edges = it.edges + GraphEdge("dep", "a", "c", "dependency")) }
        val explorer = StaticExplorer(g, scopes = mapOf("a" to "one", "b" to "one", "c" to "two"))
        explorer.selectNode("c")
        assertEquals(emptyList(), explorer.path)
        explorer.selectNode("b")
        assertEquals(listOf("e0"), explorer.path)
    }

    @Test
    fun `limit keeps a contiguous local suffix and completion does not replace it`() {
        val explorer = StaticExplorer(graph(*(0..150).map { "$it" to "${it + 1}" }.toTypedArray()))
        explorer.selectNode("151")
        assertEquals(100, explorer.path.size)
        assertEquals("151", explorer.currentNodeId)
        assertTrue(explorer.routeNotice.orEmpty().contains("100"))
        val snapshot = explorer.path
        assertEquals(StepResult.Limit, explorer.completeRoute())
        assertEquals(snapshot, explorer.path)
    }

    @Test
    fun `many diamond paths use bounded reachability rather than path enumeration`() {
        val pairs = (0 until 330).flatMap { i -> listOf("m$i" to "a$i", "m$i" to "b$i", "a$i" to "m${i + 1}", "b$i" to "m${i + 1}") }
        val capacity = graph(*(pairs + List(3000 - pairs.size) { pairs[it % pairs.size] }).toTypedArray()).let {
            it.copy(nodes = it.nodes + (1..9).map { i -> GraphNode("isolated$i", "独立$i") })
        }
        assertEquals(1000, capacity.nodes.size)
        assertEquals(3000, capacity.edges.size)
        val explorer = StaticExplorer(capacity)
        assertTimeout(Duration.ofSeconds(5)) {
            explorer.selectNode("m330")
            assertTrue((explorer.completeRoute() as StepResult.Choose).edges.size >= 2)
        }
    }

    @Test
    fun `marked entry takes precedence over degree even when the entry has incoming edges`() {
        val explorer = StaticExplorer(graph("outside" to "entry", "entry" to "work", "work" to "entry", starts = setOf("entry")))
        explorer.selectNode("work")
        assertEquals(listOf("e1"), explorer.path)
        assertTrue(explorer.canComplete)
    }

    @Test
    fun `unreachable business entry is not replaced by a degree based invented entry`() {
        val explorer = StaticExplorer(graph("local" to "declared", "local" to "tail", starts = setOf("declared")))
        explorer.selectNode("tail")
        assertEquals(emptyList(), explorer.path)
        assertEquals("tail", explorer.currentNodeId)
        assertTrue(explorer.routeNotice != null)
    }

    @Test
    fun `click uses longest compatible actual prefix and ignores memory outside target reachability`() {
        val g = graph("a" to "b", "b" to "merge", "x" to "merge", "merge" to "target", "b" to "side")
        val explorer = StaticExplorer(g)
        explorer.selectNode("b")
        explorer.selectNode("side")
        explorer.selectNode("target")
        assertEquals(listOf("e0", "e1", "e3"), explorer.path)
        assertEquals("target", explorer.currentNodeId)
        assertFalse(explorer.isChoosingIncoming)
    }
}
