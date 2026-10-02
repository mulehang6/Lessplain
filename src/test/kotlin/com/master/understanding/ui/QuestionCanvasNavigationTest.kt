package com.master.understanding.ui

import com.master.understanding.diagram.DiagramCluster
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestionCanvasNavigationTest {
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
