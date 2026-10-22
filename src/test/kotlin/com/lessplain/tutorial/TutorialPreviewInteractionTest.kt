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
    @Test fun `tab actions use platform icons and ordinary text wheel reaches the real scroll container`() = fixture { service ->
        val record = service.create("ctx_interaction", "页面", (1..100).joinToString("\n\n") { "第${it}段 中文正文，让滚轮滚动整篇教程。" }, TutorialResourceState())
        val fileEditor = TutorialEditorProvider().createEditor(service.project, TutorialVirtualFile(record.id, record.title))
        val view = fileEditor.component as TutorialEditorView
        try {
            val actions = assertNotNull(fileEditor.tabActions).getChildren(null)
            assertEquals(listOf(AllIcons.General.LayoutEditorOnly, AllIcons.General.LayoutEditorPreview, AllIcons.General.LayoutPreviewOnly), actions.map { it.templatePresentation.icon })
            actions.forEachIndexed { index, action ->
                val event = AnActionEvent.createFromAnAction(action, null, "LessplainTest", DataContext.EMPTY_CONTEXT)
                (action as ToggleAction).setSelected(event, true)
                assertEquals(listOf(TutorialDisplayMode.EDIT, TutorialDisplayMode.SPLIT, TutorialDisplayMode.PREVIEW)[index], view.mode)
            }
            view.setSize(900, 600)
            view.addNotify() // 只创建轻量 peer，不创建或显示 Window，覆盖 AWT 向祖先重新派发的真实路径。
            repeat(3) { layout(view) }
            view.preview.setSize(view.previewScroll.viewport.extentSize.width, view.preview.preferredSize.height)
            repeat(2) { layout(view) }
            assertTrue(view.preview.isDisplayable)
            val scroll = view.previewScroll.verticalScrollBar
            val before = scroll.value
            val ordinary = wheel(view.preview, 1)
            view.preview.dispatchEvent(ordinary)
            assertTrue(scroll.value > before, "正文滚动失败：before=$before after=${scroll.value} max=${scroll.maximum} extent=${scroll.visibleAmount} pane=${view.preview.size} viewport=${view.previewScroll.viewport.extentSize} textListeners=${view.preview.mouseWheelListeners.map { it.javaClass.name }} scrollUI=${view.previewScroll.ui.javaClass.name} scrollListeners=${view.previewScroll.mouseWheelListeners.map { it.javaClass.name }} consumed=${ordinary.isConsumed}")
            val position = scroll.value
            val size = view.editor.colorsScheme.editorFontSize
            view.preview.dispatchEvent(wheel(view.preview, -1, InputEvent.CTRL_DOWN_MASK))
            assertEquals(size + 1, view.editor.colorsScheme.editorFontSize)
            assertEquals(position, scroll.value)
            assertEquals(1, view.componentCount, "正文上方不再存在额外按钮条")
        } finally { view.removeNotify(); fileEditor.dispose() }
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
                val settings = com.intellij.ide.ui.UISettings.getInstance()
                val animated = settings.animatedScrolling
                settings.getState().animatedScrolling = false // 测试 JVM 关闭延迟插值，确保此 EDT 内可检查真实原生滚动结果。
                try { test(service) } finally { settings.getState().animatedScrolling = animated }
            } }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }
}
