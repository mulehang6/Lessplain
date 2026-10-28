package com.lessplain.tutorial

import com.intellij.openapi.command.WriteCommandAction
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import java.awt.Point
import kotlin.test.*

class TutorialPreviewPositionTest {
    @Test fun `preview selection and reading anchors follow a batch of document edits before refresh`() {
        TutorialMcpFixture("LessplainPreviewPosition").use { fixture ->
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx_position", "阅读", emptyList()))
            val markdown = (0..80).joinToString("\n\n") { "第${it}段 中文😀 与正文。" }
            val record = service.create("ctx_position", "阅读位置", markdown, TutorialResourceState())
            lateinit var view: TutorialEditorView
            fixture.onEdt { uiRead { view = TutorialEditorView(fixture.project, record.id) } }
            try {
                fixture.onEdt { uiRead {
                    val pane = view.preview
                    view.previewScroll.setSize(760, 320); view.previewScroll.doLayout()
                    pane.setSize(720, 9000); pane.doLayout()
                    val quote = "第40段 中文😀"
                    val from = pane.text.indexOf(quote)
                    pane.select(from, from + quote.length)
                    view.previewScroll.viewport.viewPosition = Point(0, requireNotNull(pane.modelToView2D(from)).bounds.y)
                    val oldTop = pane.topSource()
                    val first = "新开头😀\n\n"
                    val second = "再加一段中文\n\n"
                    WriteCommandAction.runWriteCommandAction(fixture.project) {
                        view.editor.document.insertString(0, first)
                        view.editor.document.insertString(0, second)
                    }
                    view.refresh()
                    assertEquals(quote, pane.selectedText)
                    assertEquals(markdown.indexOf(quote) + first.length + second.length, pane.selectedSource()?.first)
                    assertEquals(oldTop + first.length + second.length, pane.topSource())
                    val source = assertNotNull(pane.selectedSource())
                    WriteCommandAction.runWriteCommandAction(fixture.project) { view.editor.document.deleteString(source.first, source.last + 1) }
                    view.refresh()
                    assertNull(pane.selectedSource(), "整段原文删除后清空选区，不选择旁边的新文字")
                } }
            } finally { fixture.onEdt { uiRead { view.close() } } }
        }
    }
}
