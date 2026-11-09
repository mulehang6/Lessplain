package com.lessplain.tutorial

import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import com.lessplain.ui.NodeExplanationPanel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*
import com.lessplain.diagram.activateNode
import com.lessplain.diagram.selectUnderstandingNode
import com.lessplain.ui.PanelCanvasView
import com.lessplain.ide.uiRead

class NodeExplanationTest {
    @Test fun `node selection navigates without entering explanation and explicit actions open it`() {
        TutorialMcpFixture("ExplanationActivation").use { fixture ->
            val sourceId = fixture.source()["sourceId"]!!.jsonPrimitive.content
            val anchor = fixture.tutorials.sources.anchor(sourceId)
            val context = fixture.call("lessplain_context_capture", buildJsonObject { put("question", "讲解入口") })["contextId"]!!.jsonPrimitive.content
            val tutorial = fixture.call("lessplain_tutorial_create", buildJsonObject {
                put("contextId", context); put("title", "细讲"); put("markdown", "正文")
            })["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val record = fixture.graphs.core.present(4, "activation", "节点选择",
                UnderstandingGraph(listOf(GraphNode("a", "有细讲", anchor = anchor, tutorialId = tutorial), GraphNode("b", "无细讲", anchor = anchor)), emptyList()), "root")
            fixture.graphs.storage.loadRecord(record.id)
            fixture.onEdt { uiRead {
                val entered = mutableListOf<String>()
                val canvas = PanelCanvasView(fixture.graphs, record, null, null, null, { _, _ -> }, { _, id -> entered += id })
                try {
                    canvas.diagram.selectUnderstandingNode("a", notify = true)
                    assertTrue(entered.isEmpty())
                    assertTrue(canvas.explain.isVisible)
                    canvas.explain.doClick()
                    assertEquals(listOf("a"), entered)
                    assertTrue(canvas.diagram.activateNode("a"))
                    assertEquals(listOf("a", "a"), entered)
                    canvas.diagram.selectUnderstandingNode("b", notify = true)
                    assertFalse(canvas.explain.isVisible)
                    assertFalse(canvas.diagram.activateNode("b"))
                } finally { canvas.close() }
            } }
        }
    }
    @Test
    fun `graph references one editable tutorial and rejects missing tutorial references`() {
        TutorialMcpFixture("ExplanationMcp").use { fixture ->
            val source = fixture.source()["sourceId"]!!.jsonPrimitive.content
            val context = fixture.call("lessplain_context_capture", buildJsonObject { put("question", "解释入口") })["contextId"]!!.jsonPrimitive.content
            val tutorial = fixture.call("lessplain_tutorial_create", buildJsonObject {
                put("contextId", context); put("title", "入口细讲"); put("markdown", "对象来自窗口状态。")
                putJsonArray("folderPath") { add("从用户消息新建会话"); add("节点细讲") }
            })["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(2, fixture.tutorials.catalog.snapshot().count { it.tutorialId == null })
            assertEquals(1, fixture.tutorials.catalog.snapshot().count { it.tutorialId == tutorial })
            fun graph(id: String) = buildJsonObject {
                putJsonArray("nodes") { addJsonObject { put("id", "entry"); put("label", "入口"); put("sourceId", source); put("tutorialId", id) } }
                putJsonArray("edges") {}
            }
            val created = fixture.call("lessplain_graph_create", buildJsonObject {
                put("contextId", context); put("title", "流程"); put("graph", graph(tutorial))
            })
            val id = created["id"]!!.jsonPrimitive.content
            assertEquals(tutorial, fixture.graphs.repository.getRecord(id).graph.nodes.single().tutorialId)
            val read = fixture.call("lessplain_item_read", buildJsonObject { put("id", id) })
            assertEquals(tutorial, read["graph"]!!.jsonObject["nodes"]!!.jsonArray.single().jsonObject["tutorialId"]!!.jsonPrimitive.content)
            assertTrue(fixture.invoke("lessplain_graph_revise", buildJsonObject {
                put("id", id); put("expectedRevision", 1); put("graph", graph("tut_missing"))
            }).isError)
            assertEquals(1, fixture.graphs.repository.getRecord(id).revision)
            fixture.call("lessplain_graph_revise", buildJsonObject {
                put("id", id); put("expectedRevision", 1)
                put("graph", buildJsonObject {
                    putJsonArray("nodes") { addJsonObject { put("id", "entry"); put("label", "修正入口标签"); put("sourceId", source) } }
                    putJsonArray("edges") {}
                })
            })
            assertEquals(tutorial, fixture.graphs.repository.getRecord(id).graph.nodes.single().tutorialId)
        }
    }

    @Test
    fun `full panel shares canonical document and keeps tab reading independent after returning to graph`() {
        TutorialMcpFixture("ExplanationPanel").use { fixture ->
            fixture.source()
            val context = fixture.call("lessplain_context_capture", buildJsonObject { put("question", "细讲") })["contextId"]!!.jsonPrimitive.content
            val tutorial = fixture.call("lessplain_tutorial_create", buildJsonObject {
                put("contextId", context); put("title", "细讲"); put("markdown", "# 标题\n\n原有正文和上下文。")
            })["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val record = UnderstandingRecord("record", 1, null, null, "问题", "", context,
                UnderstandingGraph(listOf(GraphNode("a", "第一步", tutorialId = tutorial), GraphNode("b", "另一分支")), emptyList()), 0)
            fixture.onEdt {
                fixture.tutorials.store.saveReading(tutorial, TutorialReadingState(caret = 1))
                val panel = NodeExplanationPanel(fixture.graphs, record, "a", {})
                try {
                    assertSame(fixture.tutorials.document(tutorial), panel.view!!.editor.document)
                    panel.view!!.editor.caretModel.moveToOffset(4)
                    assertEquals(1, fixture.tutorials.store.reading(tutorial).caret)
                    fixture.tutorials.edit(tutorial, 1, listOf(TutorialEdit("原有正文和上下文。", "整合补问后的解释。")))
                    assertContains(panel.view!!.editor.document.text, "整合补问后的解释。")
                } finally { panel.close() }
                assertEquals(4, fixture.tutorials.store.reading(tutorial, panel = true).caret)
                val reopened = NodeExplanationPanel(fixture.graphs, record, "a", {})
                try { assertEquals(4, reopened.view!!.editor.caretModel.offset) } finally { reopened.close() }
            }
        }
    }
}
