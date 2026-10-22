package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialAnnotationPanelTest {
    @Test fun `persistent popup can be prepared hidden reused and disposed without creating a visible window`() = fixture { service ->
        val record = service.create("ctx_panel", "浮窗", "中文 Flow", TutorialResourceState())
        val note = service.newAnnotation(record.id, 0, 2, "note")
        val owner = javax.swing.JPanel()
        val originalListeners = owner.hierarchyListeners.toSet()
        val windows = TutorialAnnotationWindows(service.project, record.id, service, owner)
        try {
            windows.open(note); windows.open(note)
            windows.active(false); windows.active(true)
            assertEquals(1, service.store.annotations(record.id).size)
        } finally { windows.close() }
        assertEquals(originalListeners, owner.hierarchyListeners.toSet())
    }

    @Test fun `notes have icon controls autosave and confirmed deletion selects the remaining page`() = fixture { service ->
        val record = service.create("ctx_panel", "笔记", "中文 Flow", TutorialResourceState())
        val note = service.newAnnotation(record.id, 0, 2, "note")
        var confirm = false
        var closes = 0
        val panel = TutorialAnnotationPanel(service, note, {}, { closes++ }, { confirm })
        try {
            assertEquals(4, panel.controls.size)
            assertTrue(panel.controls.all { it.templatePresentation.icon != null && !it.templatePresentation.description.isNullOrBlank() })
            assertNull(panel.split.secondComponent)
            assertFalse(panel.submit.parent.isVisible)
            assertFalse(panel.materialLink.isVisible)
            panel.input.text = "原来的笔记"
            assertEquals("原来的笔记", service.store.annotation(note.id).text)
            panel.addPage()
            val second = panel.currentId
            panel.input.text = "第二条"
            panel.deleteCurrent()
            assertEquals(2, service.store.annotations(record.id).size, "取消确认不能删除")
            confirm = true
            panel.deleteCurrent()
            assertEquals(note.id, panel.currentId)
            assertEquals("原来的笔记", panel.input.text)
            assertTrue(panel.counter.text.startsWith("1/1"))
            assertEquals("NOT_FOUND", assertFailsWith<TutorialProblem> { service.store.annotation(second) }.code)
            panel.deleteCurrent()
            assertEquals(1, closes)
            assertTrue(service.store.annotations(record.id).isEmpty())
        } finally { panel.close() }
    }

    @Test fun `answer refresh preserves input and branch deletion closes descendants without touching other notes`() = fixture { service ->
        val record = service.create("ctx_panel", "追问", "中文 Flow", TutorialResourceState())
        val question = service.newAnnotation(record.id, 0, 2, "question")
        val note = service.newAnnotation(record.id, 0, 2, "note")
        var child: TutorialAnnotation? = null
        var confirmedCount = 0
        var closes = 0
        val panel = TutorialAnnotationPanel(service, question, { child = it }, {}, { count -> confirmedCount = count; true })
        var childPanel: TutorialAnnotationPanel? = null
        try {
            panel.input.text = "正在输入😀"
            panel.input.caretPosition = 3
            service.store.updateAnnotation(question.id) { it.copy(status = "answered", answer = "可以继续问的回答") }
            panel.refresh(false)
            assertEquals("正在输入😀", panel.input.text)
            assertEquals(3, panel.input.caretPosition)
            assertEquals("可以继续问的回答", panel.answer.text)
            assertNotNull(panel.split.secondComponent)
            panel.followUp("继续问的")
            val followUp = assertNotNull(child)
            assertEquals(question.id, followUp.parentId)
            assertEquals("继续问的", followUp.quote)
            childPanel = TutorialAnnotationPanel(service, followUp, {}, { closes++ }, { true })
            panel.deleteCurrent()
            childPanel.refresh(false)
            assertEquals(2, confirmedCount)
            assertEquals(1, closes)
            assertEquals(listOf(note.id), service.store.annotations(record.id).map { it.id })
        } finally { childPanel?.close(); panel.close() }
    }

    private fun fixture(test: (TutorialService) -> Unit) {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainAnnotationPanel", true).fixture
        fixture.setUp()
        try {
            ApplicationManager.getApplication().invokeAndWait { uiRead {
                val service = fixture.project.getService(TutorialService::class.java)
                service.store.saveContext(TutorialContext("ctx_panel", "测试", emptyList()))
                test(service)
            } }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }
}
