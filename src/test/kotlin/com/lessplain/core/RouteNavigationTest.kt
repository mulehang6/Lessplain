package com.lessplain.core

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.RouteNavigationState
import com.lessplain.model.UnderstandingGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteNavigationTest {
    private val graph = UnderstandingGraph(
        listOf("a", "b", "c", "d", "e", "z").map { GraphNode(it, it) },
        listOf(
            GraphEdge("ab", "a", "b", "control_flow"), GraphEdge("ac", "a", "c", "control_flow"),
            GraphEdge("bd", "b", "d", "call"), GraphEdge("cd", "c", "d", "data_flow"),
            GraphEdge("de", "d", "e", "control_flow"), GraphEdge("ze", "z", "e", "call"),
        ),
    )

    @Test
    fun `direct click expands all upstream then unique next extends only those routes`() {
        val explorer = StaticExplorer(graph)
        assertTrue(explorer.jumpTo("d"))
        assertEquals(setOf("ab", "ac", "bd", "cd"), explorer.highlightedEdgeIds)
        assertTrue(explorer.canPrevious)
        assertEquals(StepResult.Moved("e"), explorer.next())
        assertEquals(setOf("ab", "ac", "bd", "cd", "de"), explorer.highlightedEdgeIds)
        assertEquals(listOf("de"), explorer.path)
        assertFalse(explorer.canNext)
        assertEquals(setOf("de", "ze"), (explorer.previous() as StepResult.Choose).edges.map { it.id }.toSet())
        assertEquals(StepResult.Moved("d"), explorer.choose("de", StepDirection.PREVIOUS))
        assertEquals(setOf("ab", "ac", "bd", "cd"), explorer.highlightedEdgeIds)
    }

    @Test
    fun `selected forward branch stays narrow when clicking the same node again`() {
        val explorer = StaticExplorer(graph)
        assertTrue(explorer.next() is StepResult.Choose)
        explorer.choose("ab")
        explorer.next()
        assertEquals(setOf("ab", "bd"), explorer.highlightedEdgeIds)
        assertTrue(explorer.jumpTo("d"))
        assertEquals(listOf("ab", "bd"), explorer.path)
        assertEquals(setOf("ab", "bd"), explorer.highlightedEdgeIds)
    }

    @Test
    fun `backward merge asks even for a just travelled edge and permits another branch`() {
        val explorer = StaticExplorer(graph)
        explorer.choose("ab")
        explorer.next()
        assertEquals(setOf("bd", "cd"), (explorer.previous() as StepResult.Choose).edges.map { it.id }.toSet())
        assertEquals("d", explorer.currentNodeId)
        assertEquals(StepResult.Moved("c"), explorer.choose("cd", StepDirection.PREVIOUS))
        assertEquals(setOf("ac"), explorer.highlightedEdgeIds)
        assertEquals(StepResult.Moved("d"), explorer.next())
        assertEquals(setOf("ac", "cd"), explorer.highlightedEdgeIds)
        assertTrue(explorer.previous() is StepResult.Choose)
    }

    @Test
    fun `another predecessor rebases onto its own entry`() {
        val explorer = StaticExplorer(graph)
        explorer.choose("ab")
        explorer.next()
        explorer.next()
        assertEquals(StepResult.Moved("z"), explorer.choose("ze", StepDirection.PREVIOUS))
        assertEquals("z", explorer.navigationState.prefixNodeId)
        assertEquals(emptySet(), explorer.highlightedEdgeIds)
        assertEquals(StepResult.Moved("e"), explorer.next())
        assertEquals(setOf("ze"), explorer.highlightedEdgeIds)
    }

    @Test
    fun `same target parallel edges ask in both directions and retain exact edge id`() {
        val graph = UnderstandingGraph(
            listOf(GraphNode("a", "A"), GraphNode("d", "D")), listOf(
                GraphEdge("first", "a", "d", "control_flow", "首次"),
                GraphEdge("retry", "a", "d", "control_flow", "重试"),
            )
        )
        val explorer = StaticExplorer(graph)
        assertEquals(2, (explorer.next() as StepResult.Choose).edges.size)
        explorer.choose("retry")
        assertEquals(setOf("retry"), explorer.highlightedEdgeIds)
        assertEquals(2, (explorer.previous() as StepResult.Choose).edges.size)
        explorer.choose("first", StepDirection.PREVIOUS)
        assertEquals("a", explorer.currentNodeId)
        assertTrue(explorer.next() is StepResult.Choose)
    }

    @Test
    fun `snapshot preserves ambiguous prefix and chosen suffix while stale snapshots unfold saved node`() {
        val explorer = StaticExplorer(graph, savedNodeId = "d")
        explorer.next()
        val restored = StaticExplorer(graph, explorer.path, "e", explorer.navigationState)
        assertEquals(explorer.highlightedEdgeIds, restored.highlightedEdgeIds)
        assertEquals(RouteNavigationState("d", listOf("de")), restored.navigationState)
        val invalid = StaticExplorer(graph, listOf("de"), "e", RouteNavigationState("missing", listOf("de")))
        assertEquals(emptyList(), invalid.path)
        assertEquals(setOf("ab", "ac", "bd", "cd", "de", "ze"), invalid.highlightedEdgeIds)
        assertFalse(restored.jumpTo("missing"))
        assertEquals("e", restored.currentNodeId)
    }

    @Test
    fun `default entry respects start role even with incoming loop`() {
        val graph = UnderstandingGraph(
            listOf(GraphNode("b", "B"), GraphNode("a", "A", flowRoles = listOf("start"))), listOf(
                GraphEdge("ab", "a", "b", "call"), GraphEdge("ba", "b", "a", "control_flow"),
            )
        )
        assertEquals("a", StaticExplorer(graph).currentNodeId)
    }

    @Test
    fun `scoped prefix and steps exclude cross record edges`() {
        val scopes = mapOf("a" to "first", "b" to "first", "d" to "first", "e" to "first",
            "c" to "second", "z" to "second")
        val explorer = StaticExplorer(graph, scopes = scopes)

        explorer.jumpTo("d")
        assertEquals(setOf("ab", "bd"), explorer.highlightedEdgeIds)
        assertEquals(listOf("bd"), explorer.availableEdges(StepDirection.PREVIOUS).map { it.id })
        assertEquals(StepResult.Moved("e"), explorer.next())
        assertEquals(setOf("ab", "bd", "de"), explorer.highlightedEdgeIds)
        assertEquals(StepResult.Moved("d"), explorer.previous())
        assertEquals(StepResult.InvalidChoice, explorer.choose("cd", StepDirection.PREVIOUS))
        assertEquals("d", explorer.currentNodeId)
        assertEquals(RouteNavigationState("d"), explorer.navigationState)
    }

    @Test
    fun `long acyclic routes are not limited by the repeated visit bound`() {
        val graph = UnderstandingGraph(
            nodes = (0..150).map { GraphNode("n$it", "节点 $it") },
            edges = (0 until 150).map { GraphEdge("e$it", "n$it", "n${it + 1}", "control_flow") },
        )
        val explorer = StaticExplorer(graph)

        repeat(150) { assertEquals(StepResult.Moved("n${it + 1}"), explorer.next()) }
        assertEquals(150, explorer.path.size)
        assertEquals(explorer.navigationState,
            StaticExplorer(graph, explorer.path, explorer.currentNodeId, explorer.navigationState).navigationState)
    }

    @Test
    fun `structure before a marked start and dependency after it can be read and restored`() {
        val graph = UnderstandingGraph(
            listOf(GraphNode("registered", "登记"), GraphNode("tool", "工具", flowRoles = listOf("start")),
                GraphNode("store", "存储")),
            listOf(GraphEdge("registration", "registered", "tool", "structure"),
                GraphEdge("storage", "tool", "store", "dependency")),
        )
        val explorer = StaticExplorer(graph)
        assertEquals("tool", explorer.currentNodeId)
        assertTrue(explorer.canPrevious)
        assertEquals(StepResult.Moved("registered"), explorer.previous())
        assertFalse(explorer.canPrevious)
        assertTrue(explorer.canNext)
        assertEquals(StepResult.Moved("tool"), explorer.next())
        assertEquals(StepResult.Moved("store"), explorer.next())
        assertEquals(listOf("registration", "storage"), explorer.path)
        assertEquals(explorer.navigationState,
            StaticExplorer(graph, explorer.path, "store", explorer.navigationState).navigationState)
        assertEquals(StepResult.Moved("tool"), explorer.previous())
        assertEquals(listOf("registration"), explorer.path)
    }

    @Test
    fun `mixed structural and dependency alternatives require a choice and completion preserves types`() {
        val graph = UnderstandingGraph(listOf("root", "module", "store").map { GraphNode(it, it) }, listOf(
            GraphEdge("contains", "root", "module", "structure"),
            GraphEdge("requires", "root", "store", "dependency"),
            GraphEdge("uses", "module", "store", "dependency"),
        ))
        val explorer = StaticExplorer(graph)
        assertEquals(listOf("contains", "requires"), (explorer.next() as StepResult.Choose).edges.map { it.id })
        assertEquals(StepResult.Moved("module"), explorer.choose("contains"))
        assertEquals(StepResult.Moved("store"), explorer.next())
        assertEquals(listOf("requires", "uses"), (explorer.previous() as StepResult.Choose).edges.map { it.id })
        explorer.choose("uses", StepDirection.PREVIOUS)
        assertEquals(listOf("contains"), explorer.path)
        val partial = StaticExplorer(graph, savedNodeId = "module")
        assertEquals(StepResult.Moved("module"), partial.completeRoute())
        assertEquals(listOf("contains"), partial.path)
        assertEquals(listOf("structure", "dependency", "dependency"), graph.edges.map { it.relation })
        val scoped = StaticExplorer(graph, savedNodeId = "root",
            scopes = mapOf("root" to "a", "module" to "a", "store" to "b"))
        assertEquals(StepResult.Moved("module"), scoped.next())
        assertEquals(StepResult.End, scoped.next())
        assertEquals(setOf("contains"), scoped.highlightedEdgeIds)
    }
}
