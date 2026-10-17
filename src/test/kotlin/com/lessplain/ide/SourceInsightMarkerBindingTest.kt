package com.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.experiment.model.EditorSourceBinding
import com.lessplain.experiment.model.JsonPayload
import com.lessplain.experiment.model.ResolvedSource
import com.lessplain.model.SourceAnchor
import com.lessplain.model.SourceSelectionRange
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 通过真实编辑器选区与 range marker 验证源码节点的归属范围。 */
class SourceInsightMarkerBindingTest {
    private lateinit var fixture: CodeInsightTestFixture

    @BeforeEach
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("SourceInsightMarkerBindingTest").fixture)
        fixture.setUp()
    }

    @AfterEach
    fun tearDown() = fixture.tearDown()

    @Test
    fun `native capture freezes actual second selection after a non BMP character`() = onEdt {
        val text = "// 😀\nfun target() { consume(item, item) }"
        fixture.configureByText("Selection.kt", text)
        val start = text.lastIndexOf("item")
        fixture.editor.selectionModel.setSelection(start, start + 4)
        val anchor = assertNotNull(captureAnchorSnapshot(fixture.editor, "Selection.kt", "target"))
        assertEquals("item", anchor.text)
        assertEquals(2, anchor.lineHint)
        assertEquals(SourceSelectionRange(start, start + 4), anchor.selectionRange)
    }

    @Test
    fun `only complete selections inside the live source range inherit its binding`() {
        val text = "// outside\nfun target() { call() }\nfun other() { call() }\n"
        val excerpt = "fun target() { call() }"
        val prefix = "// inserted before source\n"
        var marker: SourceInsightMarker? = null
        var lifetime: Disposable? = null
        var binding: EditorSourceBinding? = null
        var start = 0
        var end = 0

        try {
            onEdt {
                fixture.configureByText("SourceBinding.kt", text)
                val editor = fixture.editor
                start = editor.document.text.indexOf(excerpt)
                end = start + excerpt.length
                val source = ResolvedSource("SourceBinding.kt", excerpt, "target", "version", 2)
                val anchor = SourceAnchor("SourceBinding.kt", "target", excerpt, "version", 2)
                val resolution = SourceResolution(
                    LocatedSource(LocationStatus.EXACT, EvidenceStatus.UNCHANGED, start),
                    fixture.file.virtualFile,
                    editor.document,
                    editor.document.modificationStamp,
                )
                val frozen = EditorSourceBinding(
                    "view:binding-test", 1, "component", null, "source-1", "target", JsonPayload.empty(), source,
                    componentType = "CodeExcerpt",
                )
                binding = frozen
                lifetime = Disposer.newDisposable("Source insight binding test")
                val sourceMarker = SourceInsightMarker(fixture.project, checkNotNull(lifetime))
                marker = sourceMarker
                assertNotNull(sourceMarker.show(anchor, resolution, frozen.label, SourceInlayNavigation(false, false, {}, {}), frozen))

                editor.selectionModel.setSelection(start + 1, end - 1)
                assertEquals(frozen, sourceMarker.bindingFor(editor))
                editor.selectionModel.setSelection(start, end)
                assertEquals(frozen, sourceMarker.bindingFor(editor), "完整选中源码范围也应保留绑定")

                editor.selectionModel.setSelection(start - 1, start + 2)
                assertNull(sourceMarker.bindingFor(editor), "越过源码范围起点的选区不能继承绑定")
                editor.selectionModel.setSelection(end - 2, end + 1)
                assertNull(sourceMarker.bindingFor(editor), "越过源码范围终点的选区不能继承绑定")

                val otherStart = editor.document.text.indexOf("fun other")
                editor.selectionModel.setSelection(otherStart, otherStart + "fun other()".length)
                assertNull(sourceMarker.bindingFor(editor), "同一文件的另一段代码不能继承绑定")
                editor.selectionModel.removeSelection()
                assertNull(sourceMarker.bindingFor(editor), "只有光标没有选区时不能继承绑定")

                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    editor.document.insertString(0, prefix)
                }
            }

            onEdt {
                val editor = fixture.editor
                val sourceMarker = checkNotNull(marker)
                val frozen = checkNotNull(binding)
                val movedStart = start + prefix.length
                val movedEnd = end + prefix.length
                editor.selectionModel.setSelection(start + 1, end - 1)
                assertNull(sourceMarker.bindingFor(editor), "应按移动后的 range marker 范围判断，不能继续使用旧 offset")
                editor.selectionModel.setSelection(movedStart + 1, movedEnd - 1)
                assertEquals(frozen, sourceMarker.bindingFor(editor), "随源码移动的范围仍应保留绑定")
            }
        } finally {
            onEdt {
                marker?.dispose()
                lifetime?.let { Disposer.dispose(it) }
            }
        }
    }

    private fun onEdt(body: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait({ body() }, ModalityState.nonModal())
    }
}
