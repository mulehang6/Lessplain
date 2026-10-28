package com.lessplain.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.QuestionOriginTarget
import com.lessplain.ide.QuestionOrigin
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.uiRead
import com.lessplain.model.SourceAnchor
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import kotlin.test.*

class QuestionOriginWindowTest {
    @Test fun `exact targets reuse one window and reject late results after switching deleting and closing`() = fixture { project, service ->
        val owner = object : JPanel() { override fun isShowing() = true }
        val callbacks = mutableListOf<(Result<QuestionOrigin>) -> Unit>()
        val shown = mutableListOf<JBPopup>()
        val window = QuestionOriginWindow(service, project,
            dispatch = { _, done -> callbacks += done; AutoCloseable {} },
            present = { popup, _ -> shown += popup })
        try {
            val a = QuestionOriginTarget("answer-a", "a"); val b = QuestionOriginTarget("answer-b", "b")
            assertTrue(callbacks.isEmpty(), "未打开时不能自动读取或弹窗")
            window.show(owner, a)
            assertEquals(1, callbacks.size)
            assertTrue(shown.single().isPersistent)
            window.show(owner, b)
            callbacks[0](Result.success(QuestionOrigin("old", "旧层", null, "old-answer")))
            assertEquals(2, callbacks.size)
            assertEquals("", window.panel?.snapshot?.text?.text)
            callbacks[1](Result.success(QuestionOrigin("new", "第二层问题", null, "answer")))
            assertEquals("第二层问题", window.panel?.snapshot?.text?.text)
            assertEquals(1, shown.size, "点击其他标题切换同一浮窗")
            window.removeDeleted(setOf("unrelated"))
            assertNotNull(window.panel)
            window.removeDeleted(setOf("answer-b"))
            shown.single().cancel()
            assertNull(window.panel)
            assertEquals(2, callbacks.size)
            window.show(owner, a)
            assertEquals(3, callbacks.size)
            window.close()
            callbacks[2](Result.success(QuestionOrigin("late", "迟到问题", null, "late")))
            assertNull(window.panel)
            assertTrue(shown.last().isDisposed)
        } finally { window.close() }
    }

    @Test fun `single question shows only its full text and frozen readonly code`() = fixture { project, _ ->
        val panel = QuestionOriginPanel(project)
        try {
            val anchor = SourceAnchor("Original.kt", null, "冻结中文代码😀\n".repeat(40), "snapshot", 1)
            val origin = QuestionOrigin("q", "问题全文\n".repeat(20), anchor, "answer")
            panel.loading(); panel.refresh(origin)
            assertEquals(origin.question, panel.snapshot.text.text)
            assertFalse(panel.snapshot.text.isEditable)
            assertEquals(anchor.text, panel.snapshot.codePreview?.editor?.document?.text)
            assertTrue(requireNotNull(panel.snapshot.codePreview).editor.isViewer)
            panel.failed("来源已删除")
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
