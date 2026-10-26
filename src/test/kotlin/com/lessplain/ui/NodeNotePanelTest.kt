package com.lessplain.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingQuestion
import org.junit.jupiter.api.Test
import kotlin.test.*

class NodeNotePanelTest {
    @Test fun `native node pages show full notes frozen code and icon actions without changing the canvas`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainNodeDetails", true).fixture
        fixture.setUp()
        try {
            ApplicationManager.getApplication().invokeAndWait { uiRead {
                val first = UnderstandingQuestion("q1", "完整中文注释\n".repeat(80), "ctx1", "r", "pending", 1)
                val second = first.copy(id = "q2", text = "第二条说明", contextToken = "ctx2")
                val anchor = SourceAnchor("Snapshot.kt", "run", "println(\"冻结源码\")\n".repeat(60), "snapshot", 1)
                var added = 0
                var edited: UnderstandingQuestion? = null
                var deleted: UnderstandingQuestion? = null
                var continued: String? = null
                val panel = NodeNotePanel(fixture.project, false, { added++ }, { edited = it }, { continued = it }, { deleted = it })
                try {
                    panel.setSize(600, 440)
                    val original = NodeNoteState(listOf(first, second), anchors = mapOf(first.id to anchor))
                    panel.refresh(original)
                    assertEquals(first.text, panel.snapshot.text.text)
                    val code = assertNotNull(panel.snapshot.codePreview)
                    assertEquals(anchor.text, code.editor.document.text)
                    assertTrue(code.expanded)
                    assertFalse(code.toggle.isVisible)
                    assertFalse(panel.previous.isEnabled)
                    assertTrue(panel.next.isEnabled)
                    assertTrue(listOf(panel.previous, panel.next, panel.addButton, panel.editButton, panel.deleteButton).all { it.icon != null && it.text.isNullOrEmpty() })
                    panel.editButton.doClick(); assertEquals(first, edited)
                    panel.next.doClick()
                    assertEquals(second.text, panel.snapshot.text.text)
                    assertNull(panel.snapshot.codePreview)
                    assertFalse(panel.next.isEnabled)
                    panel.refresh(original.copy(notes = listOf(second, first)))
                    assertEquals(second.text, panel.snapshot.text.text, "后台刷新保留当前问题身份")
                    panel.deleteButton.doClick(); assertEquals(second, deleted)
                    panel.addButton.doClick(); assertEquals(1, added)
                    panel.refresh(NodeNoteState(drafts = listOf(NodeNoteDraft("", "未提交草稿"))))
                    assertEquals("未提交草稿", panel.snapshot.text.text)
                    assertFalse(panel.deleteButton.isEnabled)
                    panel.editButton.doClick(); assertEquals("", continued)
                    assertEquals(600, panel.width, "分页与刷新不移动或缩放宿主")
                } finally { panel.close() }
            } }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }
}
