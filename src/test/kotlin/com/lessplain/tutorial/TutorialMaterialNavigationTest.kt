package com.lessplain.tutorial

import com.intellij.openapi.fileEditor.FileEditorManager
import com.lessplain.diagram.UnderstandingVirtualFile
import com.lessplain.ide.uiRead
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialMaterialNavigationTest {
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
                    requireNotNull(panel).materialLink.doClick()
                } }
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
