package com.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.experiment.model.EditorSourceBinding
import com.lessplain.experiment.model.JsonPayload
import com.lessplain.experiment.model.ResolvedSource
import com.lessplain.model.SourceAnchor
import com.lessplain.model.SourceSelectionRange
import com.lessplain.storage.sha256
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 通过真实编辑器选区与 range marker 验证节点上下文归属和选区精确性。 */
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
        assertEquals("Selection.kt", anchor.path)
        assertEquals("item", anchor.text)
        assertEquals(sha256(text), anchor.version)
        assertEquals(2, anchor.lineHint)
        assertEquals(SourceSelectionRange(start, start + 4), anchor.selectionRange)
    }

    @Test
    fun `any nonempty selection in the bound editor inherits its node context`() {
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
                    "view:binding-test", 1, "graph", "target", "source-1", "target", JsonPayload.empty(), source,
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
                assertEquals(frozen, sourceMarker.bindingFor(editor), "越过源码范围起点的选区仍沿用节点上下文")
                editor.selectionModel.setSelection(end - 2, end + 1)
                assertEquals(frozen, sourceMarker.bindingFor(editor), "越过源码范围终点的选区仍沿用节点上下文")

                val otherStart = editor.document.text.indexOf("fun other")
                editor.selectionModel.setSelection(otherStart, otherStart + "fun other()".length)
                assertEquals(frozen, sourceMarker.bindingFor(editor), "同一源码编辑器的其他代码也可关联当前节点")
                editor.selectionModel.removeSelection()
                assertNull(sourceMarker.bindingFor(editor), "只有光标没有选区时不能继承绑定")

                val otherDocument = EditorFactory.getInstance().createDocument("fun unrelated() = Unit")
                val otherEditor = EditorFactory.getInstance().createEditor(otherDocument, fixture.project)
                try {
                    otherEditor.selectionModel.setSelection(0, otherDocument.textLength)
                    assertNull(sourceMarker.bindingFor(otherEditor), "没有当前源码 marker 的其他编辑器不能继承绑定")
                } finally {
                    EditorFactory.getInstance().releaseEditor(otherEditor)
                }

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
                assertEquals(frozen, sourceMarker.bindingFor(editor), "marker 移动后，编辑器中的其他真实选区仍沿用节点上下文")
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
