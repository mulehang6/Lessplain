package com.lessplain.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.QuestionOrigin
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.uiRead
import com.lessplain.model.SourceAnchor
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import kotlin.test.*

class QuestionOriginWindowTest {
    @Test fun `history is on demand follows layers and rejects late results after switching and closing`() = fixture { project, service ->
        val owner = object : JPanel() { override fun isShowing() = true }
        val callbacks = mutableListOf<(Result<List<QuestionOrigin>>) -> Unit>()
        val shown = mutableListOf<JBPopup>()
        val window = QuestionOriginWindow(service, project,
            dispatch = { _, done -> callbacks += done; AutoCloseable {} },
            present = { popup, _ -> shown += popup })
        try {
            val a = GraphLayerKey("r", "a"); val b = GraphLayerKey("r", "b")
            window.follow(a)
            assertTrue(callbacks.isEmpty(), "未打开时不能自动读取或弹窗")
            window.show(owner, a)
            assertEquals(1, callbacks.size)
            assertTrue(shown.single().isPersistent)
            window.follow(b)
            callbacks[0](Result.success(listOf(QuestionOrigin("old", "旧层", null, "old-answer"))))
            assertEquals(2, callbacks.size)
            assertEquals("", window.panel?.snapshot?.text?.text)
            callbacks[1](Result.success(listOf(QuestionOrigin("new", "第二层问题", null, "answer"))))
            assertEquals("第二层问题", window.panel?.snapshot?.text?.text)
            assertEquals(1, shown.size, "切层更新同一浮窗")
            shown.single().cancel()
            assertNull(window.panel)
            window.follow(a)
            assertEquals(2, callbacks.size)
            window.show(owner, a)
            assertEquals(3, callbacks.size)
            window.close()
            callbacks[2](Result.success(listOf(QuestionOrigin("late", "迟到问题", null, "late"))))
            assertNull(window.panel)
            assertTrue(shown.last().isDisposed)
        } finally { window.close() }
    }

    @Test fun `pages prefer selected answer retain question identity and display only frozen readonly code`() = fixture { project, _ ->
        val panel = QuestionOriginPanel(project)
        try {
            val layer = GraphLayerKey("r", "n")
            val anchor = SourceAnchor("Original.kt", null, "冻结中文代码😀\n".repeat(40), "snapshot", 1)
            val first = QuestionOrigin("q1", "问题一全文\n".repeat(20), anchor, "answer1")
            val second = QuestionOrigin("q2", "第二个问题", null, "answer2")
            panel.loading(layer)
            panel.refresh(layer, listOf(first, second), "answer2")
            assertEquals(second.question, panel.snapshot.text.text)
            assertNull(panel.snapshot.codePreview)
            panel.previous.doClick()
            assertEquals(first.question, panel.snapshot.text.text)
            assertFalse(panel.snapshot.text.isEditable)
            assertEquals(anchor.text, panel.snapshot.codePreview?.editor?.document?.text)
            assertTrue(assertNotNull(panel.snapshot.codePreview).editor.isViewer)
            panel.refresh(layer, listOf(second, first), "answer2")
            assertEquals(first.question, panel.snapshot.text.text, "同层刷新保留当前页")
            panel.loading(GraphLayerKey("other"))
            assertEquals("", panel.snapshot.text.text)
            panel.failed("来源已删除")
            assertFalse(panel.previous.isEnabled)
            assertFalse(panel.next.isEnabled)
            assertNull(panel.snapshot.codePreview)
        } finally { panel.close() }
    }

    private fun fixture(test: (Project, UnderstandingService) -> Unit) {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainQuestionOrigin", true).fixture
        fixture.setUp()
        try { ApplicationManager.getApplication().invokeAndWait { uiRead { test(fixture.project, fixture.project.getService(UnderstandingService::class.java)) } } }
        finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }
}
