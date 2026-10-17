package com.lessplain.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.model.SourceAnchor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Container
import kotlin.test.*

/** 用实际平台编辑器验证只读、高亮、布局和释放，不依赖桌面截图。 */
class SelectedCodePreviewPanelTest {
    private lateinit var fixture: CodeInsightTestFixture

    @BeforeEach fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("CodePreviewTest").fixture)
        fixture.setUp()
    }

    @AfterEach fun tearDown() = fixture.tearDown()

    private fun preview(text: String, path: String = "missing/Snapshot.xml") =
        SelectedCodePreview(fixture.project, SourceAnchor(path, null, text, "frozen-version", 123))

    private fun onEdt(body: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait({ body() }, ModalityState.nonModal())
    }

    @Test fun `short code is immediately visible with IDE highlighting and read only selection`() = onEdt {
        val text = "<tag attr=\"中文&amp;\">\n    value\n</tag>"
        val panel = SelectedCodePreviewPanel(preview(text)) {}
        val editor = panel.editor
        try {
            assertTrue(editor.isViewer)
            assertEquals(text, editor.document.text)
            assertFalse(panel.toggle.isVisible)
            assertTrue(editor.settings.isLineNumbersShown)
            assertFalse(editor.settings.isUseSoftWraps)
            assertEquals(EditorColorsManager.getInstance().globalScheme.editorFontName, editor.colorsScheme.editorFontName)
            assertTrue(panel.heightFor(420, 800) > 0)
            editor.selectionModel.setSelection(0, text.length)
            assertEquals(text, editor.selectionModel.selectedText)
            // 通过平台测试接口检查实际词法高亮，生产组件不依赖 EditorEx。
            val iterator = (editor as EditorEx).highlighter.createIterator(0)
            val tokenTypes = mutableSetOf<String>()
            while (!iterator.atEnd()) { tokenTypes += iterator.tokenType.toString(); iterator.advance() }
            assertTrue(tokenTypes.size > 1, "XML 片段应有词法高亮，而不是一个纯文本 token")
        } finally { panel.close() }
        assertTrue(editor.isDisposed)
        assertFalse(EditorFactory.getInstance().allEditors.any { it === editor })
        panel.close()
    }

    @Test fun `long code expands below its viewport without replacing the snapshot or draft`() = onEdt {
        val text = (1..100).joinToString("\n") { "    line $it = \"中文 <>&\"" }
        val draft = CanvasTextDraft("用户的问题")
        val form = CanvasTextEditor(questionEditorTitle("目标节点"), draft, true, {}, {}, {}, {},
            selectionPreview = preview(text, "missing/Snapshot.unknown-extension"))
        val panel = form.components.filterIsInstance<SelectedCodePreviewPanel>().single()
        try {
            val collapsed = form.preferredSizeFor(420, 900)
            val collapsedHeight = panel.editor.scrollingModel.visibleArea.height
            assertEquals(6 * panel.editor.lineHeight, collapsedHeight)
            assertTrue(panel.toggle.isVisible)
            assertEquals("展开", panel.toggle.text)
            form.setSize(collapsed)
            fun layout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layout)
            }
            layout(form)
            val toggleAt = javax.swing.SwingUtilities.convertPoint(panel.toggle, 0, 0, panel)
            assertTrue(toggleAt.y >= panel.editor.component.y + panel.editor.component.height,
                "展开入口应位于代码块内部的底部")
            assertTrue(toggleAt.y + panel.toggle.height <= panel.height - panel.insets.bottom)
            assertTrue(kotlin.math.abs(toggleAt.x + panel.toggle.width / 2 - panel.width / 2) <= 1,
                "展开入口应在共同边框内水平居中")
            panel.toggle.doClick()
            val expanded = form.preferredSizeFor(420, 900)
            assertTrue(expanded.height > collapsed.height)
            assertEquals(20 * panel.editor.lineHeight, panel.editor.scrollingModel.visibleArea.height)
            panel.editor.scrollingModel.disableAnimation()
            panel.editor.scrollingModel.scrollVertically(40 * panel.editor.lineHeight)
            assertTrue(panel.editor.scrollingModel.visibleArea.y > 0)
            assertEquals("收起", panel.toggle.text)
            assertEquals(text, panel.editor.document.text)
            assertEquals("用户的问题", form.input.text)
            assertEquals("用户的问题", draft.text)
            panel.toggle.doClick()
            assertEquals(collapsed, form.preferredSizeFor(420, 900))
            form.input.text = "未保存草稿"
        } finally { form.close() }
        val restored = CanvasTextEditor(questionEditorTitle("目标节点"), draft, true, {}, {}, {}, {},
            selectionPreview = preview(text))
        try {
            assertEquals("未保存草稿", restored.input.text)
            assertEquals(text, restored.components.filterIsInstance<SelectedCodePreviewPanel>().single().editor.document.text)
        } finally { restored.close() }
    }

    @Test fun `expand footer stays one compact line after UI refresh and supports keyboard activation`() = onEdt {
        val panel = SelectedCodePreviewPanel(preview("code\n".repeat(30))) {}
        try {
            fun checkCompactFooter() {
                val size = panel.heightFor(420, 900)
                panel.setSize(420, size)
                panel.doLayout()
                (panel.toggle.parent as Container).doLayout()
                val metrics = panel.toggle.getFontMetrics(panel.toggle.font)
                val rowHeight = metrics.height + com.intellij.util.ui.JBUI.scale(2)
                assertTrue(panel.toggle.height <= rowHeight, "展开入口应仅占一行文字和极小内边距")
                val footer = panel.height - panel.insets.bottom - panel.editor.component.y - panel.editor.component.height
                assertTrue(footer <= rowHeight, "代码下方不能额外预留一整行空白")
            }
            checkCompactFooter()
            javax.swing.SwingUtilities.updateComponentTreeUI(panel)
            checkCompactFooter()
            panel.toggle.actionMap.get("pressed").actionPerformed(java.awt.event.ActionEvent(panel.toggle, 0, "pressed"))
            panel.toggle.actionMap.get("released").actionPerformed(java.awt.event.ActionEvent(panel.toggle, 0, "released"))
            assertTrue(panel.expanded, "紧凑入口仍应支持按钮的键盘激活")
            assertEquals("收起", panel.toggle.text)
            checkCompactFooter()
        } finally { panel.close() }
    }

    @Test fun `expanded preview reserves question error and buttons on a small screen`() = onEdt {
        val form = CanvasTextEditor(questionEditorTitle("节点"), CanvasTextDraft("问题\n".repeat(10)),
            true, {}, {}, {}, {}, selectionPreview = preview("code\n".repeat(100)))
        try {
            val panel = form.components.filterIsInstance<SelectedCodePreviewPanel>().single()
            panel.toggle.doClick()
            form.showErrorMessage("来源节点已删除或记录版本已改变，请重新选择")
            val size = form.preferredSizeFor(420, 320)
            form.setSize(size)
            fun layout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layout)
            }
            layout(form)
            assertTrue(size.height <= 320)
            assertTrue(panel.isVisible, "长问题不能把代码预览整个隐藏")
            assertTrue(form.input.height >= form.input.getFontMetrics(form.input.font).height)
            assertTrue(form.error.isVisible)
            assertTrue(form.saveButton.height > 0)
            assertTrue(form.cancelButton.height > 0)
            val bottom = javax.swing.SwingUtilities.convertPoint(form.saveButton, 0, form.saveButton.height, form)
            assertTrue(bottom.y <= form.height)
            assertTrue(form.preferredSizeFor(420, 120).height <= 120)
        } finally { form.close() }
    }

    @Test fun `long lines preserve indentation and Windows separators only normalize the viewer`() = onEdt {
        val text = "    " + "中文<> &\t".repeat(200) + "\r\nnext()"
        val captured = preview(text, "Snapshot.unrecognised")
        val panel = SelectedCodePreviewPanel(captured) {}
        try {
            assertEquals(text.replace("\r\n", "\n"), panel.editor.document.text)
            assertEquals(text, captured.anchor!!.text)
            assertFalse(panel.editor.settings.isUseSoftWraps)
            assertFalse(panel.toggle.isVisible)
            assertTrue(panel.heightFor(420, 600) > 0)
            assertEquals(2 * panel.editor.lineHeight, panel.editor.scrollingModel.visibleArea.height)
            panel.editor.scrollingModel.disableAnimation()
            panel.editor.scrollingModel.scrollHorizontally(200)
            assertTrue(panel.editor.scrollingModel.visibleArea.x > 0, "长行应能横向滚动")
        } finally { panel.close() }
    }

    @Test fun `open preview follows editor scheme changes and releases its scheme listener`() {
        val manager = EditorColorsManager.getInstance()
        val original = manager.globalScheme
        lateinit var panel: SelectedCodePreviewPanel
        var updates = 0
        onEdt {
            panel = SelectedCodePreviewPanel(preview("hello")) { updates++ }
            val replacement = original.clone() as EditorColorsScheme
            replacement.editorFontSize = original.editorFontSize + 2
            replacement.setColor(EditorColors.CARET_COLOR, Color.MAGENTA)
            manager.setGlobalScheme(replacement)
        }
        try {
            onEdt {
                assertTrue(updates > 0)
                assertEquals(original.editorFontSize + 2, panel.editor.colorsScheme.editorFontSize)
                assertEquals(Color.MAGENTA, panel.editor.colorsScheme.getColor(EditorColors.CARET_COLOR))
                panel.close()
                val before = updates
                manager.setGlobalScheme(original)
                assertEquals(before, updates)
            }
        } finally {
            onEdt { panel.close(); manager.setGlobalScheme(original) }
        }
    }

    @Test fun `plain node question has no code editor and uses its node in the title`() = onEdt {
        assertEquals("提问 · 准备输入与工具", questionEditorTitle("准备输入与工具"))
        val form = CanvasTextEditor(questionEditorTitle("节点"), CanvasTextDraft("普通问题"), true, {}, {}, {}, {})
        try { assertTrue(form.components.none { it is SelectedCodePreviewPanel }) } finally { form.close() }
    }
}
