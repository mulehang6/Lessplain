package com.master.understanding.ui

import com.master.understanding.diagram.DiagramCluster
import com.master.understanding.core.StaticExplorer
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.BrowseState
import com.master.understanding.model.RouteNavigationState
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestionCanvasNavigationTest {
    @Test
    fun `ambiguous upstream prefix survives a canvas source round trip after moving forward`() {
        val sourceRecord = sourceRecord("merge", "q", listOf("a", "b", "c", "d", "e"), listOf(
            "ab" to ("a" to "b"), "ac" to ("a" to "c"), "bd" to ("b" to "d"),
            "cd" to ("c" to "d"), "de" to ("d" to "e"),
        ))
        val prefix = "qq_"
        val canvas = QuestionCanvas(
            sourceRecord.copy(id = "canvas", graph = UnderstandingGraph(
                sourceRecord.graph.nodes.map { it.copy(id = prefix + it.id) },
                sourceRecord.graph.edges.map { it.copy(id = prefix + it.id, from = prefix + it.from, to = prefix + it.to) },
            )), emptyList(), sourceRecord.graph.nodes.associate { prefix + it.id to SourceNode(sourceRecord, it.id) },
            emptyMap(), emptyList(), edgeSources = sourceRecord.graph.edges.associate {
                prefix + it.id to CanvasSourceEdge(sourceRecord, it.id)
            },
        )
        val explorer = StaticExplorer(canvas.record.graph, savedNodeId = prefix + "d")
        explorer.next()
        val source = SourceNode(sourceRecord, "e")
        val sourceNavigation = requireNotNull(projectCanvasNavigationToSource(canvas, explorer.navigationState, source))
        assertEquals(RouteNavigationState("d", listOf("de")), sourceNavigation)
        val browse = BrowseState(sourceRecord.id, "e", listOf("de"), navigationState = sourceNavigation)
        val restoredBrowse = requireNotNull(projectSourceBrowseToCanvas(canvas, browse, source, prefix + "e"))
        val restored = StaticExplorer(canvas.record.graph, restoredBrowse.staticPath, restoredBrowse.nodeId,
            restoredBrowse.navigationState)
        assertEquals(explorer.navigationState, restored.navigationState)
        assertEquals(setOf("ab", "ac", "bd", "cd", "de").mapTo(linkedSetOf()) { prefix + it }, restored.highlightedEdgeIds)
        val unrelated = SourceNode(sourceRecord.copy(id = "unrelated"), "e")
        assertNull(projectCanvasNavigationToSource(canvas, explorer.navigationState, unrelated))
    }

    @Test
    fun `routes round trip within each child and reject edges from another child`() {
        val first = sourceRecord("first", "note-a", listOf("a-start", "a-parse", "a-save"),
            listOf("a-read" to ("a-start" to "a-parse"), "a-write" to ("a-parse" to "a-save")))
        val second = sourceRecord("second", "note-b", listOf("b-start", "b-parse", "b-save"),
            listOf("b-read" to ("b-start" to "b-parse"), "b-write" to ("b-parse" to "b-save")))
        val firstPrefix = "qnote-a_"
        val secondPrefix = "qnote-b_"
        val nodes = first.graph.nodes.map { it.copy(id = firstPrefix + it.id) } +
            second.graph.nodes.map { it.copy(id = secondPrefix + it.id) }
        val edges = first.graph.edges.map { it.copy(id = firstPrefix + it.id, from = firstPrefix + it.from, to = firstPrefix + it.to) } +
            second.graph.edges.map { it.copy(id = secondPrefix + it.id, from = secondPrefix + it.from, to = secondPrefix + it.to) }
        val canvas = QuestionCanvas(
            record = UnderstandingRecord("canvas", 1, null, null, "追问", "", null,
                UnderstandingGraph(nodes, edges), 0),
            clusters = listOf(
                DiagramCluster("note-a", "第一条追问", first.graph.nodes.mapTo(linkedSetOf()) { firstPrefix + it.id }),
                DiagramCluster("note-b", "第二条追问", second.graph.nodes.mapTo(linkedSetOf()) { secondPrefix + it.id }),
            ),
            sources = buildMap {
                first.graph.nodes.forEach { put(firstPrefix + it.id, SourceNode(first, it.id)) }
                second.graph.nodes.forEach { put(secondPrefix + it.id, SourceNode(second, it.id)) }
            },
            questionCounts = emptyMap(),
            examplePanels = emptyList(),
            edgeSources = buildMap {
                first.graph.edges.forEach { put(firstPrefix + it.id, CanvasSourceEdge(first, it.id)) }
                second.graph.edges.forEach { put(secondPrefix + it.id, CanvasSourceEdge(second, it.id)) }
            },
        )
        val firstSource = SourceNode(first, "a-parse")
        val secondSource = SourceNode(second, "b-parse")

        assertEquals(listOf("a-read"), projectCanvasPathToSource(canvas, listOf(firstPrefix + "a-read"), firstSource))
        assertEquals(listOf(firstPrefix + "a-read"),
            projectSourcePathToCanvas(canvas, listOf("a-read"), firstSource, firstPrefix + "a-parse"))
        assertEquals(listOf("b-read"), projectCanvasPathToSource(canvas, listOf(secondPrefix + "b-read"), secondSource))
        assertEquals(listOf(secondPrefix + "b-read"),
            projectSourcePathToCanvas(canvas, listOf("b-read"), secondSource, secondPrefix + "b-parse"))
        assertNull(projectCanvasPathToSource(canvas, listOf(firstPrefix + "a-read"), secondSource))
        assertNull(projectCanvasPathToSource(canvas,
            listOf(firstPrefix + "a-read", secondPrefix + "b-read"), secondSource))
        assertNull(projectSourcePathToCanvas(canvas, listOf("a-read", "b-write"), firstSource,
            firstPrefix + "a-parse"))
        val explorer = StaticExplorer(canvas.record.graph, scopes = canvas.sources.mapValues { it.value.record.id })
        explorer.selectNode(secondPrefix + "b-save")
        assertEquals(listOf("b-read", "b-write"),
            projectCanvasPathToSource(canvas, explorer.path, SourceNode(second, "b-save")))
        explorer.selectNode(firstPrefix + "a-save")
        assertEquals(listOf("a-read", "a-write"),
            projectCanvasPathToSource(canvas, explorer.path, SourceNode(first, "a-save")))
    }

    private fun sourceRecord(
        id: String,
        questionId: String,
        nodeIds: List<String>,
        edges: List<Pair<String, Pair<String, String>>>,
    ): UnderstandingRecord = UnderstandingRecord(
        id = id,
        revision = 1,
        parentId = "owner",
        questionId = questionId,
        question = questionId,
        answer = "",
        contextToken = null,
        graph = UnderstandingGraph(
            nodes = nodeIds.map { GraphNode(it, it) },
            edges = edges.map { (edgeId, endpoints) -> GraphEdge(edgeId, endpoints.first, endpoints.second, "control_flow") },
        ),
        createdAt = 0,
    )
}
