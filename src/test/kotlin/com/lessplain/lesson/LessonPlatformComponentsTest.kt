package com.lessplain.lesson

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JTextArea
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** 启动测试平台模型而不启动 IDE 窗口，覆盖真实编辑器和读权限。 */
class LessonPlatformComponentsTest {
    @Test fun `first render note refresh source navigation and live ranges obey platform contracts`() {
        // EMF 的进程级引用队列由图布局库首次使用时启动，不属于项目生命周期。
        Class.forName("org.eclipse.emf.common.util.CommonUtil")
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainNativeLesson", true).fixture
        fixture.setUp()
        var editor: Editor? = null
        var view: LessonView? = null
        try {
            val project = fixture.project
            val path = Path.of(requireNotNull(project.basePath)).resolve("Gateway.txt")
            val source = "fun first(): Flow<Event> = TODO()\nfun second(): Flow<Event> = TODO()"
            Files.createDirectories(path.parent)
            Files.writeString(path, source)
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            lateinit var service: LessonService
            onEdt {
                service = project.getService(LessonService::class.java)
                editor = lessonUiRead { EditorFactory.getInstance().createEditor(
                    requireNotNull(FileDocumentManager.getInstance().getDocument(file)), project) }
            }
            val loaded = CountDownLatch(1)
            service.background({ Unit }) { loaded.countDown() }
            assertTrue(loaded.await(10, TimeUnit.SECONDS), "教材服务未完成初始化")
            val start = source.indexOf("Flow")
            val focus = captureLessonFocus(source, "Gateway.txt", start, start + 4)
            val origin = LessonOrigin(SourceAnchor("Gateway.txt", null, "Flow<Event>", "v"), focus = focus)
            val context = service.engine.start(origin, "Flow", "学习")
            val binding = LessonSourceBinding("flow", "Flow", focus)
            val blocks = listOf(
                LessonBlock("intro", "text", richText = listOf(LessonTextRun("Flow", "code"), LessonTextRun(" 提供事件")), sourceBindings = listOf(binding)),
                LessonBlock("code", "code", text = "fun run(): Flow<Event>"),
                LessonBlock("figure", "diagram", graph = UnderstandingGraph(listOf(GraphNode("flow", "交付事件")), emptyList())),
            )
            val record = runBlocking { service.present("platform", context.token, "Flow", blocks, emptyList(), false, "学习") }
            assertFailsWith<IllegalArgumentException> {
                runBlocking { service.present("bad-range", context.token, "Flow", listOf(blocks.first().copy(
                    sourceBindings = listOf(binding.copy(focus = focus.copy(anchor = focus.anchor.copy(text = "不存在的来源")))))),
                    emptyList(), false, "学习") }
            }
            assertEquals(record, service.snapshot.lessons.single { it.id == record.id })
            onEdt {
                view = LessonView(project, record.id).apply { setSize(1100, 900); activated(); doLayout() }
                assertNotNull(components(view!!).firstOrNull { it is JButton && it.text == "我的笔记" })
                assertEquals(1, editor!!.markupModel.allHighlighters.count { it.textAttributes?.effectType == EffectType.LINE_UNDERSCORE })
            }
            checkCodeGeometry(project, requireNotNull(view))
            onEdt {
                (components(view!!).first { it is JButton && it.text == "我的笔记" } as JButton).doClick()
                val input = components(view!!).filterIsInstance<JTextArea>().first()
                input.text = "我自己的理解"
                (components(view!!).first { it is JButton && it.text == "保存笔记" } as JButton).doClick()
            }
            awaitCondition { service.snapshot.annotations.any { it.note == "我自己的理解" } }
            onEdt {
                WriteCommandAction.runWriteCommandAction(project) { editor!!.document.insertString(0, "// new\n") }
            }
            // 让已排队的范围刷新执行，再验证位置随编辑移动且颜色不覆盖语法。
            onEdt {
                val mark = editor!!.markupModel.allHighlighters.single { it.textAttributes?.effectType == EffectType.LINE_UNDERSCORE }
                assertEquals(start + 7, mark.startOffset)
                assertEquals(null, mark.textAttributes?.foregroundColor)
                service.navigateSource(focus.anchor, focus)
                view!!.activated()
                assertTrue(components(view!!).any { it is JButton && it.text.startsWith("▾ 关于") })
            }
            // 周围上下文发生修改时，当前会话仍由 marker 定位；关闭后不猜测新的位置。
            onEdt {
                WriteCommandAction.runWriteCommandAction(project) { editor!!.document.insertString(11, "renamed_") }
            }
            onEdt {
                service.navigateSource(focus.anchor, focus)
                assertNotNull(service.sourceStatus(service.snapshot.lessons.single { it.id == record.id }))
                FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
                val document = editor!!.document
                EditorFactory.getInstance().releaseEditor(editor!!)
                editor = lessonUiRead { EditorFactory.getInstance().createEditor(document, project) }
            }
            onEdt {
                assertEquals(0, editor!!.markupModel.allHighlighters.count { it.textAttributes?.effectType == EffectType.LINE_UNDERSCORE })
                assertTrue(service.snapshot.annotations.any { it.note == "我自己的理解" })
            }
            assertEquals(record, runBlocking {
                service.present("platform", context.token, "Flow", blocks, emptyList(), false, "学习")
            })
        } finally {
            onEdt {
                lessonUiRead { FileEditorManager.getInstance(fixture.project).openFiles.forEach {
                    FileEditorManager.getInstance(fixture.project).closeFile(it)
                } }
                view?.close()
                editor?.let { EditorFactory.getInstance().releaseEditor(it) }
            }
            onEdt { fixture.tearDown() }
        }
    }

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)

    private fun checkCodeGeometry(project: com.intellij.openapi.project.Project, view: LessonView) {
        lateinit var panel: LessonCodePanel
        lateinit var viewer: Editor
        var shortHeight = 0
        onEdt {
            assertTrue(components(view).any { it is LessonCodePanel })
            val actual = EditorFactory.getInstance().allEditors.single { it.isViewer && it.getUserData(lessonEditorSelectionKey) != null }
            assertTrue(!actual.settings.isShowIntentionBulb && !actual.settings.isCaretRowShown && !actual.settings.isLineMarkerAreaShown)
            viewer = lessonUiRead { EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument("val event = 1"), project) }
            panel = LessonCodePanel(viewer as com.intellij.openapi.editor.ex.EditorEx) { it() }
            shortHeight = panel.preferredSize.height
        }
        try {
            onEdt {
                WriteCommandAction.runWriteCommandAction(project) { viewer.document.setText("val event = 1\n".repeat(5)) }
                panel.setSize(400, 300); panel.doLayout()
            }
            awaitCondition { var grew = false; onEdt { grew = panel.preferredSize.height > shortHeight }; grew }
            onEdt {
                WriteCommandAction.runWriteCommandAction(project) { viewer.document.setText("val event = 1\n".repeat(100)) }
                panel.doLayout()
            }
            awaitCondition {
                var capped = false
                onEdt { capped = panel.preferredSize.height == viewer.lineHeight * 24 + com.intellij.util.ui.JBUI.scale(12) }
                capped
            }
            onEdt {
                val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try { lessonUiRead { panel.paint(graphics) } } finally { graphics.dispose() }
            }
        } finally { onEdt { EditorFactory.getInstance().releaseEditor(viewer) } }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition(), "平台异步操作未完成")
    }

    private fun components(root: Component): List<Component> = listOf(root) +
        if (root is Container) root.components.flatMap(::components) else emptyList()
}
