package com.lessplain.tutorial

import com.intellij.openapi.fileEditor.FileEditorManager
import com.lessplain.diagram.UnderstandingVirtualFile
import com.lessplain.ide.uiRead
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.deleteRootRecord
import com.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialMaterialNavigationTest {
    @Test fun `deleted tutorial updates only the external reference while preserving answer and draft input`() {
        TutorialMcpFixture("LessplainDeletedMaterialNavigation").use { fixture ->
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx_material", "来源", emptyList()))
            val target = service.create("ctx_material", "引用目标", "目标正文", TutorialResourceState())
            val owner = service.create("ctx_material", "保留教程", "保留正文", TutorialResourceState())
            val question = service.annotationDraft(owner.id, 0, 2, "question").copy(text = "解释一下", status = "pending", contextId = "ctx_material")
            service.store.createAnnotation(question)
            val answered = service.answer(question.id, "保留回答", target.id)
            lateinit var panel: TutorialAnnotationPanel
            fixture.onEdt { uiRead { panel = TutorialAnnotationPanel(service, answered, {}, {}) } }
            try {
                service.store.deleteTutorial(target.id)
                fixture.onEdt { uiRead {
                    val input = panel.input.text
                    val caret = panel.input.caretPosition
                    panel.refreshMaterialReference(target.id)
                    assertEquals("教程已删除", panel.materialLink.text)
                    assertFalse(panel.materialLink.isEnabled)
                    assertEquals(input, panel.input.text); assertEquals(caret, panel.input.caretPosition)
                    assertEquals("保留回答", panel.answer.text)
                } }
                assertEquals(target.id, service.store.annotation(question.id).answerMaterialId)
                assertEquals(owner, service.store.record(owner.id))
            } finally { fixture.onEdt { panel.close() } }
        }
    }

    @Test fun `deleting a referenced graph disables the existing answer link without replacing the answer`() {
        TutorialMcpFixture("LessplainDeletedGraphReference").use { fixture ->
            val text = "fun run(): Flow<Event> = TODO()"
            fixture.source(text)
            val anchor = SourceAnchor("Flow.txt", null, text, sha256(text), 1)
            val graph = fixture.graphs.core.present(4, "graph-reference", "关联图",
                UnderstandingGraph(listOf(GraphNode("n", "节点", anchor = anchor)), emptyList()), "root")
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx_graph", "来源", emptyList()))
            val record = service.create("ctx_graph", "教程", "原文", TutorialResourceState())
            val question = service.annotationDraft(record.id, 0, 2, "question").copy(text = "问题", status = "pending")
            service.store.createAnnotation(question)
            val answered = service.answer(question.id, "保留回答", graph.id)
            lateinit var panel: TutorialAnnotationPanel
            fixture.onEdt { uiRead { panel = TutorialAnnotationPanel(service, answered, {}, {}) } }
            try {
                val deleted = fixture.graphs.repository.deleteRootRecord(graph.id)
                fixture.onEdt { fixture.graphs.storage.remove(deleted.recordIds); fixture.graphs.notifyChanged() }
                val deadline = System.nanoTime() + 5_000_000_000L
                var disabled = false
                while (!disabled && System.nanoTime() < deadline) {
                    fixture.onEdt { disabled = !panel.materialLink.isEnabled && panel.materialLink.text == "记录已删除" }
                    if (!disabled) Thread.sleep(10)
                }
                assertTrue(disabled)
                fixture.onEdt { assertEquals("保留回答", panel.answer.text) }
            } finally { fixture.onEdt { panel.close() } }
        }
    }

    @Test fun `annotation material link loads graphs outside EDT then opens the editor`() {
        TutorialMcpFixture("LessplainAnnotationMaterialNavigation").use { fixture ->
            val text = "fun run(): Flow<Event> = TODO()"
            fixture.source(text)
            val anchor = SourceAnchor("Flow.txt", null, text, sha256(text), 1)
            val graph = fixture.graphs.core.present(4, "material-link", "关联图",
                UnderstandingGraph(listOf(GraphNode("n", "消息", anchor = anchor)), emptyList()), "root")
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx_material", "来源", emptyList()))
            val tutorial = service.create("ctx_material", "引用", "原文", TutorialResourceState())
            val question = service.annotationDraft(tutorial.id, 0, 2, "question").copy(text = "解释一下", status = "pending", contextId = "ctx_material")
            service.store.createAnnotation(question)
            val answered = service.answer(question.id, "看这张图", graph.id)
            var panel: TutorialAnnotationPanel? = null
            try {
                fixture.onEdt { uiRead {
                    panel = TutorialAnnotationPanel(service, answered, {}, {})
                } }
                var ready = false
                val readyDeadline = System.nanoTime() + 5_000_000_000L
                while (!ready && System.nanoTime() < readyDeadline) {
                    fixture.onEdt { ready = requireNotNull(panel).materialLink.isEnabled }
                    if (!ready) Thread.sleep(10)
                }
                assertTrue(ready, "关联图存在性检查应在后台完成")
                fixture.onEdt { uiRead { requireNotNull(panel).materialLink.doClick() } }
                var opened = false
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!opened && System.nanoTime() < deadline) {
                    fixture.onEdt { opened = FileEditorManager.getInstance(fixture.project).openFiles.filterIsInstance<UnderstandingVirtualFile>().any { it.recordId == graph.id } }
                    if (!opened) Thread.sleep(10)
                }
                assertTrue(opened, "关联图必须在读取完成后打开，而不是因 EDT 数据库访问而静默失败")
            } finally { fixture.onEdt { panel?.close() } }
        }
    }
}
