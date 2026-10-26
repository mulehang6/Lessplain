package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 不创建 IDE 窗口，验证真实 Document、原生预览、连续选区、滚动和生命周期。 */
class TutorialPlatformTest {
    @Test fun `separate preview and editor share document and preserve native selection across modes`() {
        Class.forName("org.eclipse.emf.common.util.CommonUtil")
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainSeparatedTutorial", true).fixture
        fixture.setUp()
        var view: TutorialEditorView? = null
        try {
            onEdt { uiRead {
                val service = fixture.project.getService(TutorialService::class.java)
                service.store.saveContext(TutorialContext("ctx_platform", "Flow", emptyList()))
                val code = "println(42)\n".repeat(120)
                val markdown = "# Flow\n\n**中文😀** 与 `Flow`\n\n下一段中文\n\n| 名称 | 意义 |\n| --- | --- |\n| Flow | 陆续交付 |\n\n```kotlin\n$code```\n\n末尾"
                val record = service.create("ctx_platform", "可编辑 Flow", markdown, TutorialResourceState())
                val current = TutorialEditorView(fixture.project, record.id)
                view = current
                current.setSize(800, 600); current.doLayout()
                current.preview.setSize(760, 12000)
                assertEquals(TutorialDisplayMode.PREVIEW, current.mode)
                assertTrue(current.preview.preferredSize.height > current.editor.lineHeight * 100)
                assertTrue(current.editor.foldingModel.allFoldRegions.isEmpty())
                assertTrue(current.editor.inlayModel.getBlockElementsInRange(0, markdown.length).isEmpty())
                val visible = current.preview.text
                assertFalse(visible.contains("**中文"))
                val from = visible.indexOf("中文")
                val to = visible.indexOf("下一段中文") + "下一段中文".length
                current.preview.select(from, to)
                val range = assertNotNull(current.preview.selectedSource())
                assertEquals(markdown.indexOf("中文"), range.first)
                assertEquals(markdown.indexOf("下一段中文") + "下一段中文".length, range.last + 1)
                assertEquals(range, current.actions.selection)
                current.actions.suspendForMenu()
                assertEquals(range, current.actions.selection, "菜单隐藏浮条，但不丢失冻结范围")
                current.display(TutorialDisplayMode.EDIT)
                val doc = current.editor.document
                current.editor.selectionModel.setSelection(0, 6)
                assertEquals(0 until 6, current.actions.selection)
                current.display(TutorialDisplayMode.SPLIT)
                assertSame(current.editor.component, current.split.firstComponent)
                assertSame(current.previewScroll, current.split.secondComponent)
                val updated = service.edit(record.id, 1, listOf(TutorialEdit("末尾", "修改后的结尾")))
                current.refresh()
                assertSame(doc, service.document(record.id))
                assertTrue(doc.text.endsWith("修改后的结尾"))
                assertTrue(current.preview.text.endsWith("修改后的结尾\n\n"))
                assertEquals(updated, service.edit(record.id, 1, listOf(TutorialEdit("末尾", "修改后的结尾"))))
                val note = service.annotationDraft(record.id, range.first, range.last + 1, "note")
                service.store.createAnnotation(note.copy(text = "尚在输入的笔记"))
                val question = service.annotationDraft(record.id, note.start, note.end, "question")
                service.store.createAnnotation(question.copy(text = "这是什么？", status = "pending", contextId = "ctx_platform"))
                service.answer(question.id, "这是讲解。", null)
                assertEquals("尚在输入的笔记", service.store.annotation(note.id).text)
                val image = BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().let { g -> try { current.preview.paint(g) } finally { g.dispose() } }
                current.close(); view = null
                val savedReading = service.store.reading(record.id).copy(previewSource = markdown.indexOf("下一段中文"), scroll = 200)
                service.store.saveReading(record.id, savedReading)
                val reopened = TutorialEditorView(fixture.project, record.id)
                view = reopened
                assertEquals(TutorialDisplayMode.SPLIT, reopened.mode)
                assertSame(doc, reopened.editor.document)
                assertEquals(savedReading, service.store.reading(record.id), "构造视图不能在恢复前用初始零位置覆盖存档")
            } }
        } finally { onEdt { uiRead { view?.close() }; fixture.tearDown() } }
    }

    @Test fun `same named words exact navigation editing stale ranges and reopen never choose a first match`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainTutorialSources", true).fixture
        fixture.setUp()
        try {
            val project = fixture.project
            val path = Path.of(requireNotNull(project.basePath)).resolve("Gateway.txt")
            val text = "fun first(): Flow<Event> = TODO()\nfun second(): Flow<Event> = TODO()"
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            onEdt {
                uiRead {
                    val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                    val service = project.getService(TutorialService::class.java)
                    assertEquals("AMBIGUOUS_SOURCE", assertFailsWith<TutorialProblem> { service.sources.resolve("Gateway.txt", "Flow<Event>", "Flow") }.code)
                    val first = service.sources.resolve("Gateway.txt", text.lines()[0], "Flow<Event>")
                    val second = service.sources.resolve("Gateway.txt", text.lines()[1], "Flow")
                    assertNotEquals(first.id, second.id)
                    service.sources.reveal(second.id)
                    val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
                    assertEquals("Flow", editor.selectionModel.selectedText)
                    assertEquals(text.lastIndexOf("Flow"), editor.selectionModel.selectionStart)
                    WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "// 编辑\n") }
                    service.sources.reveal(second.id)
                    assertEquals(text.lastIndexOf("Flow") + 6, editor.selectionModel.selectionStart)
                    WriteCommandAction.runWriteCommandAction(project) { editor.document.replaceString(editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd, "Other") }
                    assertEquals("SOURCE_STALE", assertFailsWith<TutorialProblem> { service.sources.reveal(second.id) }.code)
                    service.sources.reveal(first.id)
                    assertEquals("Flow<Event>", editor.selectionModel.selectedText)
                    assertTrue(editor.markupModel.allHighlighters.none { it.textAttributes?.effectType == com.intellij.openapi.editor.markup.EffectType.LINE_UNDERSCORE })
                    FileEditorManager.getInstance(project).closeFile(file)
                }
            }
        } finally { onEdt { fixture.tearDown() } }
    }

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)

    @Test fun `split view scrolls both directions by source paragraph without changing caret`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainScrollSync", true).fixture
        fixture.setUp()
        var view: TutorialEditorView? = null
        try {
            onEdt { uiRead {
                val service = fixture.project.getService(TutorialService::class.java)
                service.store.saveContext(TutorialContext("ctx_scroll", "同步", emptyList()))
                val markdown = (1..80).joinToString("\n\n") { "第${it}段 中文正文，保持段落对应。" }
                val record = service.create("ctx_scroll", "同步", markdown, TutorialResourceState())
                val current = TutorialEditorView(fixture.project, record.id)
                view = current
                current.display(TutorialDisplayMode.SPLIT)
                current.setSize(1100, 700)
                fun layout(component: java.awt.Component) {
                    if (component is java.awt.Container) { component.doLayout(); component.components.forEach(::layout) }
                }
                repeat(3) { layout(current) }
                current.preview.setSize(current.previewScroll.viewport.extentSize.width, current.preview.preferredSize.height)
                val caret = current.editor.caretModel.offset
                val first = markdown.indexOf("第20段")
                val second = markdown.indexOf("第40段")
                current.editor.scrollingModel.scrollVertically(current.editor.offsetToXY(first).y)
                assertTrue(kotlin.math.abs(current.preview.topSource() - first) < 30, "编辑滚动应定位预览的对应段落")
                current.preview.followSource(second)
                assertEquals(current.editor.offsetToXY(current.preview.topSource()).y, current.editor.scrollingModel.verticalScrollOffset)
                assertEquals(caret, current.editor.caretModel.offset)
            } }
        } finally { onEdt { uiRead { view?.close() }; fixture.tearDown() } }
    }

    @Test fun `native selection distinguishes two identical tokens inside one unique context`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainExactSelection", true).fixture
        fixture.setUp()
        try {
            val path = Path.of(requireNotNull(fixture.project.basePath)).resolve("SameLine.txt")
            val text = "fun third(input: Flow<Event>): Flow<Event> = input"
            Files.createDirectories(path.parent); Files.writeString(path, text)
            onEdt { uiRead {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                val service = fixture.project.getService(TutorialService::class.java)
                assertEquals("AMBIGUOUS_FOCUS", assertFailsWith<TutorialProblem> { service.sources.resolve("SameLine.txt", text, "Flow<Event>") }.code)
                val start = text.lastIndexOf("Flow<Event>")
                val source = service.sources.capture("SameLine.txt", text, start, start + "Flow<Event>".length)
                service.sources.reveal(source.id)
                val editor = requireNotNull(FileEditorManager.getInstance(fixture.project).selectedTextEditor)
                assertEquals(start, editor.selectionModel.selectionStart)
                assertEquals("Flow<Event>", editor.selectionModel.selectedText)
                FileEditorManager.getInstance(fixture.project).openFiles.forEach { FileEditorManager.getInstance(fixture.project).closeFile(it) }
            } }
        } finally { onEdt { fixture.tearDown() } }
    }
}
