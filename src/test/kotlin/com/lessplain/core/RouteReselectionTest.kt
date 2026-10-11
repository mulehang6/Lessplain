package com.lessplain.core

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 路线持久化与重新选择独立，直接选线不移动目标焦点。 */
class RouteReselectionTest {
    private val graph = UnderstandingGraph(
        listOf("start", "left", "right", "merge", "target").map { GraphNode(it, it) },
        listOf(
            GraphEdge("s-l", "start", "left", "call"),
            GraphEdge("s-r", "start", "right", "call"),
            GraphEdge("l-m", "left", "merge", "call"),
            GraphEdge("r-m", "right", "merge", "call"),
            GraphEdge("m-t", "merge", "target", "call"),
            GraphEdge("loop", "merge", "merge", "call"),
        ),
    )

    @Test fun `reclick keeps selected route and completion offers another choice`() {
        val explorer = StaticExplorer(graph)
        explorer.jumpTo("target")
        assertTrue(explorer.completeRoute() is StepResult.Choose)
        explorer.chooseIncoming("l-m")
        val chosen = explorer.navigationState
        explorer.jumpTo("target")
        assertEquals(chosen, explorer.navigationState)
        val choices = explorer.completeRoute() as StepResult.Choose
        assertEquals(setOf("l-m", "r-m"), choices.edges.map { it.id }.toSet())
        assertEquals(chosen, explorer.navigationState)
        assertEquals(StepResult.Moved("target"), explorer.chooseIncoming("r-m"))
        assertEquals(listOf("s-r", "r-m", "m-t"), explorer.path)
    }

    @Test fun `direct incoming edge fixes it then offers the upstream fork`() {
        val explorer = StaticExplorer(graph)
        explorer.jumpTo("target")
        val choices = explorer.completeViaIncoming("m-t") as StepResult.Choose
        assertEquals("target", explorer.currentNodeId)
        assertEquals(setOf("l-m", "r-m"), choices.edges.map { it.id }.toSet())
        explorer.chooseIncoming("r-m")
        assertEquals("target", explorer.currentNodeId)
        assertEquals(listOf("s-r", "r-m", "m-t"), explorer.path)
        explorer.jumpTo("merge")
        assertEquals(StepResult.Moved("merge"), explorer.completeViaIncoming("l-m"))
        assertEquals(listOf("s-l", "l-m"), explorer.path)
    }

    @Test fun `ordinary steps ignore self loop while graph keeps the relation`() {
        val explorer = StaticExplorer(graph)
        explorer.jumpTo("merge")
        assertEquals(listOf("m-t"), explorer.availableEdges().map { it.id })
        assertEquals(setOf("l-m", "r-m"), explorer.availableEdges(StepDirection.PREVIOUS).map { it.id }.toSet())
        assertEquals(StepResult.Moved("target"), explorer.next())
        assertEquals(StepResult.Moved("merge"), explorer.previous())
        assertTrue(graph.edges.any { it.id == "loop" })
    }
}
