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

/** 显式补全只在选择完成后提交，普通聚焦不推断路线。 */
class IncomingRouteTest {
    private fun graph(vararg edges: Pair<String, String>, starts: Set<String> = emptySet()): UnderstandingGraph {
        val ids = edges.flatMap { listOf(it.first, it.second) }.distinct()
        return UnderstandingGraph(
            ids.map { GraphNode(it, it, flowRoles = if (it in starts) listOf("start") else emptyList()) },
            edges.mapIndexed { i, (from, to) -> GraphEdge("e$i", from, to, "control_flow", "条件$i") },
        )
    }

    @Test
    fun `explicit completion fills unique route while synchronization preserves latest visit`() {
        val explorer = StaticExplorer(graph("a" to "b", "b" to "c"))
        assertTrue(explorer.jumpTo("c"))
        assertTrue(explorer.path.isEmpty())
        assertEquals(StepResult.Moved("c"), explorer.completeRoute())
        assertEquals(listOf("e0", "e1"), explorer.path)
        assertTrue(explorer.synchronizeNode("b"))
        assertEquals(listOf("e0"), explorer.path)
    }

    @Test
    fun `ambiguous completion leaves target and selected suffix unchanged until confirmed`() {
        val explorer = StaticExplorer(
            graph("email" to "lock", "wx" to "lock", "lock" to "save"),
            savedEdges = listOf("e2"),
            savedNodeId = "save",
        )
        val before = explorer.navigationState
        assertEquals(setOf("e0", "e1"), (explorer.completeRoute() as StepResult.Choose).edges.map { it.id }.toSet())
        assertEquals(before, explorer.navigationState)
        assertEquals(StepResult.Moved("save"), explorer.chooseIncoming("e0"))
        assertEquals(listOf("e0", "e2"), explorer.path)
    }

    @Test
    fun `cancel discards temporary choices and reselect preserves the confirmed route`() {
        val explorer = StaticExplorer(graph(
            "a" to "merge", "b" to "merge", "merge" to "tail", "c" to "final", "tail" to "final",
        ))
        explorer.jumpTo("final")
        explorer.completeRoute()
        assertTrue(explorer.chooseIncoming("e4") is StepResult.Choose)
        explorer.cancelIncoming()
        assertTrue(explorer.path.isEmpty())
        assertEquals(2, (explorer.completeRoute() as StepResult.Choose).edges.size)
        explorer.chooseIncoming("e4")
        explorer.chooseIncoming("e0")
        assertEquals(listOf("e0", "e2", "e4"), explorer.path)
        val confirmed = explorer.navigationState
        explorer.completeRoute()
        explorer.cancelIncoming()
        assertEquals(confirmed, explorer.navigationState)
    }

    @Test
    fun `confirmed choice is reused only when completion requests remembered choices`() {
        val explorer = StaticExplorer(graph("a" to "merge", "b" to "merge", "merge" to "tail", "x" to "y"))
        explorer.jumpTo("tail")
        explorer.completeRoute()
        explorer.chooseIncoming("e1")
        explorer.jumpTo("y")
        explorer.completeRoute()
        explorer.jumpTo("tail")
        assertTrue(explorer.path.isEmpty())
        assertEquals(StepResult.Moved("tail"), explorer.completeRoute(reselect = false))
        assertEquals(listOf("e1", "e2"), explorer.path)
        assertTrue(explorer.completeRoute() is StepResult.Choose)
    }

    @Test
    fun `back at partial boundary chooses predecessor and never takes first parallel edge`() {
        val explorer = StaticExplorer(
            graph("a" to "b", "a" to "b", "b" to "c"),
            savedEdges = listOf("e2"),
            savedNodeId = "c",
        )
        assertEquals(StepResult.Moved("b"), explorer.previous())
        val choices = explorer.previous() as StepResult.Choose
        assertEquals(setOf("e0", "e1"), choices.edges.map { it.id }.toSet())
        assertEquals(StepResult.Moved("a"), explorer.chooseIncoming("e1"))
        explorer.jumpTo("c")
        explorer.completeRoute(reselect = false)
        assertEquals(listOf("e1", "e2"), explorer.path)
    }

    @Test
    fun `business entry may have incoming loop and completion does not add laps`() {
        val explorer = StaticExplorer(graph("a" to "b", "b" to "c", "c" to "b", starts = setOf("a")))
        explorer.jumpTo("c")
        explorer.completeRoute()
        assertEquals(listOf("e0", "e1"), explorer.path)
        explorer.next()
        explorer.next()
        assertEquals(listOf("e0", "e1", "e2", "e1"), explorer.path)
        explorer.completeRoute()
        assertEquals(listOf("e0", "e1"), explorer.path)
        explorer.synchronizeNode("b")
        assertEquals(listOf("e0"), explorer.path)
    }

    @Test
    fun `isolated cycle has no invented entry and explicit stepping still cycles`() {
        val explorer = StaticExplorer(graph("a" to "b", "b" to "a"))
        explorer.jumpTo("b")
        assertEquals(StepResult.Limit, explorer.completeRoute())
        assertTrue(explorer.path.isEmpty())
        assertTrue(explorer.routeNotice != null)
        assertEquals(StepResult.Moved("a"), explorer.next())
        assertEquals(StepResult.Moved("b"), explorer.next())
        assertEquals(listOf("e1", "e0"), explorer.path)
    }

    @Test
    fun `record scopes exclude cross record edges and non browsable relations`() {
        val g = graph("a" to "b", "b" to "c").let {
            it.copy(edges = it.edges + GraphEdge("dep", "a", "c", "dependency"))
        }
        val explorer = StaticExplorer(g, scopes = mapOf("a" to "one", "b" to "one", "c" to "two"))
        explorer.jumpTo("c")
        explorer.completeRoute()
        assertTrue(explorer.path.isEmpty())
        explorer.jumpTo("b")
        explorer.completeRoute()
        assertEquals(listOf("e0"), explorer.path)
    }

    @Test
    fun `completion limit preserves the selected local suffix`() {
        val explorer = StaticExplorer(
            graph(*(0..150).map { "$it" to "${it + 1}" }.toTypedArray()),
            savedEdges = listOf("e150"),
            savedNodeId = "151",
        )
        val before = explorer.navigationState
        assertEquals(StepResult.Limit, explorer.completeRoute(reselect = false))
        assertEquals(before, explorer.navigationState)
        assertTrue(explorer.routeNotice.orEmpty().contains("100"))
        assertFalse(explorer.isChoosingIncoming)
    }

    @Test
    fun `many diamond paths use bounded reachability rather than path enumeration`() {
        val pairs = (0 until 330).flatMap { i ->
            listOf("m$i" to "a$i", "m$i" to "b$i", "a$i" to "m${i + 1}", "b$i" to "m${i + 1}")
        }
        val capacity = graph(*(pairs + List(3000 - pairs.size) { pairs[it % pairs.size] }).toTypedArray()).let {
            it.copy(nodes = it.nodes + (1..9).map { i -> GraphNode("isolated$i", "独立$i") })
        }
        assertEquals(1000, capacity.nodes.size)
        assertEquals(3000, capacity.edges.size)
        val explorer = StaticExplorer(capacity)
        assertTimeout(Duration.ofSeconds(5)) {
            explorer.jumpTo("m330")
            assertTrue((explorer.completeRoute() as StepResult.Choose).edges.size >= 2)
        }
    }

    @Test
    fun `marked entry takes precedence over degree even with incoming edges`() {
        val explorer = StaticExplorer(graph("outside" to "entry", "entry" to "work", "work" to "entry", starts = setOf("entry")))
        explorer.jumpTo("work")
        assertEquals(StepResult.Moved("work"), explorer.completeRoute())
        assertEquals(listOf("e1"), explorer.path)
    }

    @Test
    fun `unreachable business entry is not replaced by degree based invented entry`() {
        val explorer = StaticExplorer(graph("local" to "declared", "local" to "tail", starts = setOf("declared")))
        explorer.jumpTo("tail")
        assertEquals(StepResult.Limit, explorer.completeRoute())
        assertTrue(explorer.path.isEmpty())
        assertEquals("tail", explorer.currentNodeId)
        assertTrue(explorer.routeNotice != null)
    }
}
