package com.lessplain.core

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticExplorerTest {
    @Test
    fun `direct node selection unfolds upstream while synchronization preserves the latest route visit`() {
        val graph = UnderstandingGraph(
            nodes = listOf(GraphNode("a", "A"), GraphNode("b", "B"), GraphNode("c", "C"), GraphNode("d", "D")),
            edges = listOf(
                GraphEdge("ab", "a", "b", "control_flow"),
                GraphEdge("bc", "b", "c", "control_flow"),
                GraphEdge("cb", "c", "b", "control_flow"),
                GraphEdge("bd", "b", "d", "control_flow"),
            ),
        )
        val explorer = StaticExplorer(graph)

        assertEquals(StepResult.Moved("b"), explorer.next())
        assertEquals(StepResult.Moved("c"), explorer.choose("bc"))
        assertEquals(StepResult.Moved("b"), explorer.next())
        assertEquals(listOf("ab", "bc", "cb"), explorer.path)

        assertTrue(explorer.synchronizeNode("b"))
        assertEquals(listOf("ab", "bc", "cb"), explorer.path)
        assertTrue(explorer.synchronizeNode("c"))
        assertEquals(listOf("ab", "bc"), explorer.path)
        assertTrue(explorer.jumpTo("d"))
        assertEquals("d", explorer.currentNodeId)
        assertEquals(emptyList(), explorer.path)
        assertEquals(StepResult.End, explorer.next())
    }

    @Test
    fun `saved route is restored only when continuous and ending at saved node`() {
        val graph = UnderstandingGraph(
            nodes = listOf(GraphNode("a", "A"), GraphNode("b", "B"), GraphNode("c", "C"),
                GraphNode("x", "X"), GraphNode("y", "Y")),
            edges = listOf(
                GraphEdge("ab", "a", "b", "call"),
                GraphEdge("bc", "b", "c", "data_flow"),
                GraphEdge("xy", "x", "y", "structural"),
            ),
        )

        val restored = StaticExplorer(graph, listOf("ab", "bc"), "c")
        assertEquals(listOf("ab", "bc"), restored.path)
        assertEquals("c", restored.currentNodeId)

        val discontinuous = StaticExplorer(graph, listOf("ab", "xy"), "y")
        assertEquals(emptyList(), discontinuous.path)
        assertEquals("y", discontinuous.currentNodeId)

        val wrongEndpoint = StaticExplorer(graph, listOf("ab"), "c")
        assertEquals(emptyList(), wrongEndpoint.path)
        assertEquals("c", wrongEndpoint.currentNodeId)

        val nodeOnly = StaticExplorer(graph, savedNodeId = "c")
        assertEquals(emptyList(), nodeOnly.path)
        assertEquals("c", nodeOnly.currentNodeId)
    }

    @Test
    fun `selected node in another graph does not infer that graphs root`() {
        val graph = UnderstandingGraph(
            nodes = listOf(GraphNode("main", "主图"), GraphNode("branch", "独立入口"), GraphNode("done", "完成")),
            edges = listOf(GraphEdge("branch-done", "branch", "done", "call")),
        )
        val explorer = StaticExplorer(graph)

        assertEquals("main", explorer.currentNodeId)
        assertTrue(explorer.jumpTo("done"))
        assertEquals("done", explorer.currentNodeId)
        assertEquals(emptyList(), explorer.path)
        assertEquals(StepResult.End, explorer.next())
        assertTrue(explorer.jumpTo("branch"))
        assertEquals(StepResult.Moved("done"), explorer.next())
        assertEquals(listOf("branch-done"), explorer.path)
    }

    @Test
    fun `route from second entry survives merge and publish back step`() {
        val graph = UnderstandingGraph(
            nodes = listOf(
                GraphNode("request_entry", "请求入口"),
                GraphNode("callback_entry", "回调入口"),
                GraphNode("email_parse", "邮件解析"),
                GraphNode("callback_guard", "回调校验"),
                GraphNode("callback_idempotent", "幂等检查"),
                GraphNode("callback_save", "保存回调"),
                GraphNode("publish", "发布"),
                GraphNode("confirm_save", "保存确认"),
            ),
            edges = listOf(
                GraphEdge("request-email-parse", "request_entry", "email_parse", "call"),
                GraphEdge("callback-email-parse", "callback_entry", "email_parse", "call"),
                GraphEdge("email-guard", "email_parse", "callback_guard", "control_flow"),
                GraphEdge("guard-idempotent", "callback_guard", "callback_idempotent", "control_flow"),
                GraphEdge("idempotent-save", "callback_idempotent", "callback_save", "data_flow"),
                GraphEdge("save-publish", "callback_save", "publish", "control_flow", condition = "首次发布"),
                GraphEdge(
                    "save-publish-retry",
                    "callback_save",
                    "publish",
                    "control_flow",
                    condition = "重试发布",
                ),
                GraphEdge("save-confirm", "callback_save", "confirm_save", "control_flow", condition = "等待确认"),
            ),
        )
        val explorer = StaticExplorer(graph)

        assertTrue(explorer.jumpTo("callback_entry"))
        assertEquals(StepResult.Moved("email_parse"), explorer.next())
        assertTrue(explorer.synchronizeNode(explorer.currentNodeId))
        assertEquals(StepResult.Moved("callback_guard"), explorer.next())
        assertTrue(explorer.synchronizeNode(explorer.currentNodeId))
        assertEquals(StepResult.Moved("callback_idempotent"), explorer.next())
        assertTrue(explorer.synchronizeNode(explorer.currentNodeId))
        assertEquals(StepResult.Moved("callback_save"), explorer.next())
        assertTrue(explorer.synchronizeNode(explorer.currentNodeId))
        assertEquals(
            listOf("callback-email-parse", "email-guard", "guard-idempotent", "idempotent-save"),
            explorer.path,
        )

        val choices = explorer.next() as StepResult.Choose
        assertEquals(setOf("save-publish", "save-publish-retry", "save-confirm"), choices.edges.map { it.id }.toSet())
        assertEquals(StepResult.Moved("publish"), explorer.choose("save-publish-retry"))
        assertEquals("save-publish-retry", explorer.path.last())
        assertEquals("重试发布", graph.edges.single { it.id == explorer.path.last() }.condition)

        assertTrue(explorer.previous() is StepResult.Choose)
        assertEquals(StepResult.Moved("callback_save"), explorer.choose("save-publish-retry", StepDirection.PREVIOUS))
        assertEquals("callback_save", explorer.currentNodeId)
        assertEquals(
            listOf("callback-email-parse", "email-guard", "guard-idempotent", "idempotent-save"),
            explorer.path,
        )
    }

    @Test
    fun `loop keeps the one hundred step limit after restoring and stepping back`() {
        val graph = UnderstandingGraph(
            nodes = listOf(GraphNode("a", "循环入口"), GraphNode("b", "循环处理")),
            edges = listOf(GraphEdge("ab", "a", "b", "control_flow"), GraphEdge("ba", "b", "a", "control_flow")),
        )
        val explorer = StaticExplorer(graph)

        repeat(101) { index -> assertEquals(StepResult.Moved(if (index % 2 == 0) "b" else "a"), explorer.next()) }
        assertEquals(101, explorer.path.size)
        assertEquals(StepResult.Limit, explorer.next())
        val restored = StaticExplorer(graph, explorer.path, explorer.currentNodeId, explorer.navigationState)
        assertTrue(restored.back())
        assertEquals(StepResult.Moved("b"), restored.next())
        assertEquals(101, restored.path.size)
        assertTrue(restored.back())
        assertEquals(100, restored.path.size)
    }
}
