package com.lessplain.tutorial

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JTextPane
import kotlin.test.*

class TutorialPreviewInteractionTest {
    @Test fun `tab actions use platform icons and ordinary text wheel reaches the real scroll container`() {
        TutorialMcpFixture("LessplainPreviewWheel").use { fixture ->
            var fileEditor: com.intellij.openapi.fileEditor.FileEditor? = null
            var view: TutorialEditorView? = null
            try {
                fixture.onEdt { uiRead {
                    val service = fixture.tutorials
                    service.store.saveContext(TutorialContext("ctx_interaction", "测试", emptyList()))
                    val record = service.create("ctx_interaction", "页面", (1..100).joinToString("\n\n") { "第${it}段 中文正文，让滚轮滚动整篇教程。" }, TutorialResourceState())
                    val opened = TutorialEditorProvider().createEditor(service.project, TutorialVirtualFile(record.id, record.title))
                    fileEditor = opened
                    val current = opened.component as TutorialEditorView
                    view = current
                    val actions = assertNotNull(opened.tabActions).getChildren(null)
                    assertEquals(listOf(AllIcons.General.LayoutEditorOnly, AllIcons.General.LayoutEditorPreview, AllIcons.General.LayoutPreviewOnly), actions.map { it.templatePresentation.icon })
                    actions.forEachIndexed { index, action ->
                        val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, "LessplainTest", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
                        (action as ToggleAction).setSelected(event, true)
                        assertEquals(listOf(TutorialDisplayMode.EDIT, TutorialDisplayMode.SPLIT, TutorialDisplayMode.PREVIEW)[index], current.mode)
                    }
                    current.setSize(900, 600)
                    current.addNotify() // 只创建轻量 peer，不创建或显示 Window，覆盖 AWT 向祖先重新派发的真实路径。
                    repeat(3) { layout(current) }
                    current.preview.setSize(current.previewScroll.viewport.extentSize.width, current.preview.preferredSize.height)
                    repeat(2) { layout(current) }
                    assertTrue(current.preview.isDisplayable)
                } }
                val scrolled = java.util.concurrent.CountDownLatch(1)
                var before = 0
                fixture.onEdt { uiRead {
                    val current = requireNotNull(view)
                    val bar = current.previewScroll.verticalScrollBar
                    before = bar.value
                    bar.addAdjustmentListener { if (bar.value > before) scrolled.countDown() }
                    current.preview.dispatchEvent(wheel(current.preview, 1))
                } }
                assertTrue(scrolled.await(5, java.util.concurrent.TimeUnit.SECONDS), "正文滚轮应驱动真实滚动容器，包括平台异步动画")
                fixture.onEdt { uiRead {
                    val current = requireNotNull(view)
                    val scroll = current.previewScroll.verticalScrollBar
                    assertTrue(scroll.value > before)
                    val position = scroll.value
                    val size = current.editor.colorsScheme.editorFontSize
                    current.preview.dispatchEvent(wheel(current.preview, -1, InputEvent.CTRL_DOWN_MASK))
                    assertEquals(size + 1, current.editor.colorsScheme.editorFontSize)
                    assertEquals(position, scroll.value)
                    assertEquals(1, current.componentCount, "正文上方不再存在额外按钮条")
                } }
            } finally { fixture.onEdt { view?.removeNotify(); fileEditor?.dispose() } }
        }
    }

    @Test fun `syntax changes do not split background paint and wrapping creates one shape per visual row`() = fixture { service ->
        val record = service.create("ctx_interaction", "绘制", "正文", TutorialResourceState())
        val view = TutorialEditorView(service.project, record.id)
        try {
            val code = "val message = \"中文😀\"; println(message)"
            val text = JTextPane().apply { this.text = code; font = view.editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN) }
            styleTutorialCode(text.styledDocument, 0, code, "kotlin", view.editor)
            assertTrue(text.styledDocument.defaultRootElement.getElement(0).elementCount > 4)
            val shapes = mutableListOf<Rectangle>()
            text.highlighter.addHighlight(0, code.length, TutorialRangePainter { _, bounds -> shapes += bounds })
            fun paint(width: Int): List<Rectangle> {
                shapes.clear(); text.setSize(width, 500)
                val image = BufferedImage(width, 500, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().let { g -> try { text.paint(g) } finally { g.dispose() } }
                return shapes.toList()
            }
            val wide = paint(1000)
            assertEquals(1, wide.size, "同一显示行即使有多个语法片段，也只能绘制一条连续背景")
            val first = text.modelToView2D(0)
            val last = text.modelToView2D(code.length)
            assertTrue(wide.single().width >= last.x - first.x - 1)
            val narrow = paint(140)
            assertTrue(narrow.size > 1)
            assertEquals(narrow.size, narrow.map { it.y }.distinct().size)
        } finally { view.close() }
    }

    @Test fun `links require a real text hit and never open while text is selected`() = fixture { service ->
        val href = "https://example.invalid/details"
        val record = service.create("ctx_interaction", "链接", "[查看说明]($href)", TutorialResourceState())
        val view = TutorialEditorView(service.project, record.id)
        try {
            val preview = view.preview
            preview.setSize(900, 400)
            val first = assertNotNull(preview.modelToView2D(0)).bounds
            val inside = java.awt.Point(first.x + 1, first.y + first.height / 2)
            assertEquals(href, preview.linkAt(inside))
            val end = assertNotNull(preview.modelToView2D(preview.document.length)).bounds
            val whitespace = java.awt.Point(end.x + 80, first.y + first.height / 2)
            assertNull(preview.hoverSourceAt(whitespace))
            assertNull(preview.linkAt(whitespace))
            preview.select(0, 2)
            assertNull(preview.linkAt(inside))
        } finally { view.close() }
    }

    private fun wheel(component: Component, rotation: Int, modifiers: Int = 0) = MouseWheelEvent(component,
        MouseWheelEvent.MOUSE_WHEEL, 0, modifiers, 50, 20, 50, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation)
    private fun layout(component: Component) {
        if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
    }
    private fun fixture(test: (TutorialService) -> Unit) {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainPreviewInteraction", true).fixture
        fixture.setUp()
        try {
            ApplicationManager.getApplication().invokeAndWait { uiRead {
                val service = fixture.project.getService(TutorialService::class.java)
                service.store.saveContext(TutorialContext("ctx_interaction", "测试", emptyList()))
                test(service)
            } }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }
}
