package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialAnnotationPanelTest {
    @Test fun `persistent popup can be prepared hidden reused and disposed without creating a visible window`() = fixture { service ->
        val record = service.create("ctx_panel", "浮窗", "中文 Flow", TutorialResourceState())
        val note = service.annotationDraft(record.id, 0, 2, "note")
        val owner = javax.swing.JPanel()
        val originalListeners = owner.hierarchyListeners.toSet()
        val windows = TutorialAnnotationWindows(service.project, record.id, service, owner)
        try {
            windows.open(note); windows.open(note)
            windows.active(false); windows.active(true)
            assertTrue(service.store.annotations(record.id).isEmpty())
        } finally { windows.close() }
        assertEquals(originalListeners, owner.hierarchyListeners.toSet())
    }

    @Test fun `notes have icon controls autosave and confirmed deletion selects the remaining page`() {
        TutorialMcpFixture("LessplainAnnotationDeletePages").use { fixture ->
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx_panel", "测试", emptyList()))
            val record = service.create("ctx_panel", "笔记", "中文 Flow", TutorialResourceState())
            val note = service.annotationDraft(record.id, 0, 2, "note")
            var confirm = false
            var closes = 0
            lateinit var panel: TutorialAnnotationPanel
            lateinit var subscription: AutoCloseable
            fixture.onEdt { uiRead {
                panel = TutorialAnnotationPanel(service, note, {}, { closes++ }, { confirm })
                subscription = service.onChanged { if (it == record.id) panel.refresh(false) }
            } }
            try {
                fixture.onEdt { uiRead {
                    assertEquals(4, panel.controls.size)
                    assertTrue(panel.controls.all { it.icon != null && !it.toolTipText.isNullOrBlank() && !it.accessibleContext.accessibleName.isNullOrBlank() })
                    assertNull(panel.split.secondComponent)
                    assertFalse(panel.submit.parent.isVisible)
                    assertFalse(panel.materialLink.isVisible)
                    panel.input.text = "原来的笔记"
                    assertEquals("原来的笔记", service.store.annotation(note.id).text)
                    panel.addPage()
                    panel.input.text = "第二条"
                    panel.deleteCurrent()
                    assertEquals(2, service.store.annotations(record.id).size, "取消确认不能删除")
                    confirm = true
                    panel.deleteCurrent()
                } }
                await(fixture) { panel.currentId == note.id && service.store.annotations(record.id).size == 1 }
                fixture.onEdt { uiRead {
                    assertEquals("原来的笔记", panel.input.text)
                    assertTrue(panel.counter.text.startsWith("1/1"))
                    panel.deleteCurrent()
                } }
                await(fixture) { closes == 1 && service.store.annotations(record.id).isEmpty() }
            } finally { fixture.onEdt { subscription.close(); panel.close() } }
        }
    }

    @Test fun `answer refresh preserves input and branch deletion closes descendants without touching other notes`() {
        TutorialMcpFixture("LessplainAnnotationBranchDelete").use { fixture ->
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx_panel", "测试", emptyList()))
            val record = service.create("ctx_panel", "追问", "中文 Flow", TutorialResourceState())
            val question = service.annotationDraft(record.id, 0, 2, "question")
            val note = service.store.createAnnotation(service.annotationDraft(record.id, 0, 2, "note").copy(text = "保留的笔记"))
            var child: TutorialAnnotation? = null
            var confirmedCount = 0
            var closes = 0
            lateinit var panel: TutorialAnnotationPanel
            var childPanel: TutorialAnnotationPanel? = null
            lateinit var subscription: AutoCloseable
            fixture.onEdt { uiRead {
                panel = TutorialAnnotationPanel(service, question, { child = it }, {}, { count -> confirmedCount = count; true })
                subscription = service.onChanged { if (it == record.id) { panel.refresh(false); childPanel?.refresh(false) } }
            } }
            try {
                fixture.onEdt { uiRead {
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
                    childPanel = TutorialAnnotationPanel(service, followUp, {}, { closes++; childPanel?.close() }, { true }).also { it.input.text = "后续问题" }
                    panel.deleteCurrent()
                    assertEquals(2, confirmedCount)
                } }
                await(fixture) { closes == 1 && service.store.annotations(record.id).map { it.id } == listOf(note.id) }
            } finally { fixture.onEdt { subscription.close(); childPanel?.close(); panel.close() } }
        }
    }

    @Test fun `blank pages stay local repeat add reuses one page and clearing keeps the editor`() = fixture { service ->
        val record = service.create("ctx_panel", "空白批注", "中文 Flow", TutorialResourceState())
        val draft = service.annotationDraft(record.id, 0, 2, "question")
        var closes = 0
        val panel = TutorialAnnotationPanel(service, draft, {}, { closes++ }, { true })
        try {
            assertTrue(service.store.annotations(record.id).isEmpty())
            assertTrue(panel.counter.text.contains("新建"))
            assertFalse(panel.submit.isEnabled)
            assertNotNull(panel.submit.icon)
            assertTrue(panel.submit.text.isNullOrEmpty())
            val input = panel.input
            val document = input.document
            panel.addPage(); panel.addPage()
            assertEquals(draft.id, panel.currentId)
            assertTrue(panel.counter.text.startsWith("1/1"))
            input.text = "  \n"
            assertTrue(service.store.annotations(record.id).isEmpty())
            input.text = "为什么😀"
            assertEquals("为什么😀", service.store.annotation(draft.id).text)
            assertTrue(panel.submit.isEnabled)
            panel.addPage()
            val blank = panel.currentId
            panel.addPage()
            assertEquals(blank, panel.currentId)
            assertEquals(1, service.store.annotations(record.id).size)
            panel.page(-1)
            input.text = ""
            assertTrue(service.store.annotations(record.id).isEmpty())
            assertEquals(draft.id, panel.currentId)
            assertEquals(0, closes)
            assertSame(input, panel.input)
            assertSame(document, panel.input.document)
            assertTrue(panel.counter.text.startsWith("1/1"))
            assertTrue(panel.counter.text.contains("新建"))
            input.text = "恢复草稿"
            panel.close()
            val reopened = TutorialAnnotationPanel(service, service.store.annotation(draft.id), {}, {}, { true })
            try { assertEquals("恢复草稿", reopened.input.text) } finally { reopened.close() }
        } finally { panel.close() }
    }

    @Test fun `submitted question is readonly and clearing through a stale editor cannot remove it`() = fixture { service ->
        val record = service.create("ctx_panel", "保护追问", "中文 Flow", TutorialResourceState())
        val draft = service.annotationDraft(record.id, 0, 2, "question")
        val panel = TutorialAnnotationPanel(service, draft, {}, {}, { true })
        try {
            panel.input.text = "为什么"
            service.submitQuestion(draft.id)
            panel.refresh(false)
            assertFalse(panel.input.isEditable)
            assertFalse(panel.submit.parent.isVisible)
            assertEquals("pending", service.store.saveAnnotationDraft(draft, "", false)?.status)
            service.answer(draft.id, "回答", null)
            assertEquals("answered", service.store.saveAnnotationDraft(draft, "", false)?.status)
            assertEquals("为什么", service.store.annotation(draft.id).text)
        } finally { panel.close() }
    }

    private fun await(fixture: TutorialMcpFixture, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            var done = false
            fixture.onEdt { done = condition() }
            if (done) return
            Thread.sleep(10)
        }
        fail("批注删除及窗口更新未完成")
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
