package com.lessplain.ui

import com.lessplain.diagram.DiagramCluster
import com.lessplain.diagram.createUnderstandingDiagram
import com.lessplain.ide.uiRead
import com.lessplain.model.GraphCluster
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.tutorial.TutorialMcpFixture
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 工具窗口根图与编辑器使用相同的持久分组，追问画布保留自己的分组。 */
class PanelCanvasViewTest {
    @Test
    fun `root canvas preserves saved clusters just like the editor`() {
        TutorialMcpFixture("PanelRootClusters").use { fixture ->
            val sourceId = fixture.source()["sourceId"]!!.jsonPrimitive.content
            val anchor = fixture.tutorials.sources.anchor(sourceId)
            val graph = UnderstandingGraph(
                nodes = listOf(GraphNode("a", "准备", anchor = anchor), GraphNode("b", "显示", anchor = anchor)),
                edges = listOf(GraphEdge("ab", "a", "b", "control_flow")),
                clusters = listOf(
                    GraphCluster("prepare", "准备阶段", listOf("a")),
                    GraphCluster("show", "显示阶段", listOf("b")),
                ),
            )
            val record = fixture.graphs.core.present(4, "root-clusters", "分组一致", graph, "root")
            fixture.graphs.storage.loadRecord(record.id)
            fixture.onEdt { uiRead {
                val panel = PanelCanvasView(fixture.graphs, record, null, null, null, { _, _ -> })
                val editorDiagram = createUnderstandingDiagram(record)
                try {
                    val expected = listOf(
                        DiagramCluster("prepare", "准备阶段", setOf("a")),
                        DiagramCluster("show", "显示阶段", setOf("b")),
                    )
                    assertEquals(expected, panel.diagram.clusterSpecs)
                    assertEquals(editorDiagram.clusterSpecs, panel.diagram.clusterSpecs)
                } finally {
                    panel.close()
                    editorDiagram.dispose()
                }
            } }
        }
    }

    @Test
    fun `question canvas keeps its explicit groups instead of owner root groups`() {
        TutorialMcpFixture("PanelQuestionClusters").use { fixture ->
            val sourceId = fixture.source()["sourceId"]!!.jsonPrimitive.content
            val anchor = fixture.tutorials.sources.anchor(sourceId)
            val graph = UnderstandingGraph(
                nodes = listOf(GraphNode("a", "原节点", anchor = anchor)),
                edges = emptyList(),
                clusters = listOf(GraphCluster("owner", "原图分组", listOf("a"))),
            )
            val owner = fixture.graphs.core.present(4, "owner-clusters", "原图", graph, "root")
            fixture.graphs.storage.loadRecord(owner.id)
            val answer = owner.copy(
                id = "questions:${owner.id}:a",
                parentId = owner.id,
                graph = UnderstandingGraph(listOf(GraphNode("answer", "回答")), emptyList()),
            )
            val groups = listOf(DiagramCluster("question", "独立追问", setOf("answer")))
            val canvas = QuestionCanvas(answer, groups, emptyMap(), emptyMap(), emptyList())
            fixture.onEdt { uiRead {
                val panel = PanelCanvasView(fixture.graphs, owner, "a", null, canvas, { _, _ -> })
                try {
                    assertEquals(groups, panel.diagram.clusterSpecs)
                } finally {
                    panel.close()
                }
            } }
        }
    }
}
