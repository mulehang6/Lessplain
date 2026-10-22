package com.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.model.SourceAnchor
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 原生 Document 与 Editor 的范围跟踪，覆盖没有编辑器和重新打开的窗口。 */
class SourceInsightTrackingTest {
    @Test fun `editing before evidence moves hints in existing split and reopened editors`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainSourceHintTracking", true).fixture
        fixture.setUp()
        val factory = EditorFactory.getInstance()
        val lifetime = Disposer.newDisposable()
        val editors = mutableListOf<Editor>()
        try {
            lateinit var marker: SourceInsightMarker
            val document = factory.createDocument("// 前面的说明\n    fun example() = 1\n")
            val excerpt = "fun example() = 1"
            val binding = SourceNodeBinding("record", 1, "example", "示例", emptyList())
            onEdt { uiRead {
                marker = SourceInsightMarker(fixture.project, lifetime)
                Disposer.register(lifetime, marker)
                val resolution = SourceResolution(LocatedSource(LocationStatus.EXACT, EvidenceStatus.UNCHANGED, document.text.indexOf(excerpt)),
                    document = document, modificationStamp = document.modificationStamp)
                marker.show(SourceAnchor("Example.kt", null, excerpt, "snapshot"), resolution, "示例函数", navigation(), binding)
                WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "// 新增前文\n") }
                editors += factory.createEditor(document, fixture.project)
            } }
            javax.swing.SwingUtilities.invokeAndWait { }
            onEdt { uiRead {
                val first = editors.single()
                assertEquals(binding, first.getUserData(SOURCE_NODE_BINDING), "编辑器创建事件应安装来源；高亮=${first.markupModel.allHighlighters.map { it.customRenderer?.javaClass?.name }}")
                val range = first.markupModel.allHighlighters.single { it.customRenderer is SourceSweepRenderer }
                assertEquals(document.text.indexOf(excerpt), range.startOffset)
                assertEquals(binding, first.getUserData(SOURCE_NODE_BINDING))
                WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(range.startOffset, "    ") }
                val inlay = first.inlayModel.getBlockElementsInRange(0, document.textLength).single()
                val renderer = inlay.renderer as InsightInlayRenderer
                assertEquals(sourceBlockBounds(first, range.startOffset, range.endOffset)!!.x, renderer.hintBounds(inlay).x)
                editors += factory.createEditor(document, fixture.project)
            } }
            javax.swing.SwingUtilities.invokeAndWait { }
            onEdt { uiRead {
                editors.forEach { editor ->
                    val range = editor.markupModel.allHighlighters.single { it.customRenderer is SourceSweepRenderer }
                    assertEquals(document.text.indexOf(excerpt), range.startOffset)
                    assertEquals(excerpt, document.charsSequence.subSequence(range.startOffset, range.endOffset).toString())
                }
                editors.forEach(factory::releaseEditor); editors.clear()
                WriteCommandAction.runWriteCommandAction(fixture.project) { document.deleteString(0, document.text.indexOf(excerpt)) }
                editors += factory.createEditor(document, fixture.project)
            } }
            javax.swing.SwingUtilities.invokeAndWait { }
            onEdt { uiRead {
                assertEquals(0, editors.single().markupModel.allHighlighters.single { it.customRenderer is SourceSweepRenderer }.startOffset)
                assertEquals(binding, editors.single().getUserData(SOURCE_NODE_BINDING))
            } }
        } finally { onEdt { uiRead { Disposer.dispose(lifetime); editors.forEach(factory::releaseEditor) }; fixture.tearDown() } }
    }

    @Test fun `changed excerpt without an editor never installs a stale hint or binding`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainStaleSourceHint", true).fixture
        fixture.setUp()
        val factory = EditorFactory.getInstance()
        val lifetime = Disposer.newDisposable()
        var editor: Editor? = null
        var invalidations = 0
        try {
            val document = factory.createDocument("val original = 1")
            onEdt { uiRead {
                val marker = SourceInsightMarker(fixture.project, lifetime)
                Disposer.register(lifetime, marker)
                marker.show(SourceAnchor("Example.kt", null, document.text, "snapshot"),
                    SourceResolution(LocatedSource(LocationStatus.EXACT, EvidenceStatus.UNCHANGED, 0), document = document, modificationStamp = document.modificationStamp),
                    "旧说明", navigation { invalidations++ }, SourceNodeBinding("record", 1, "node", "旧来源", emptyList()))
                WriteCommandAction.runWriteCommandAction(fixture.project) { document.setText("val replacement = 2") }
                editor = factory.createEditor(document, fixture.project)
            } }
            javax.swing.SwingUtilities.invokeAndWait { }
            onEdt { uiRead {
                assertEquals(1, invalidations)
                val current = assertNotNull(editor)
                assertNull(current.getUserData(SOURCE_NODE_BINDING))
                assertTrue(current.markupModel.allHighlighters.none { it.customRenderer is SourceSweepRenderer })
                assertTrue(current.inlayModel.getBlockElementsInRange(0, document.textLength).isEmpty())
            } }
        } finally { onEdt { uiRead { Disposer.dispose(lifetime); editor?.let(factory::releaseEditor) }; fixture.tearDown() } }
    }

    private fun navigation(invalidated: () -> Unit = {}) = SourceInlayNavigation(false, false, {}, {}, evidenceChanged = invalidated)
    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)
}
