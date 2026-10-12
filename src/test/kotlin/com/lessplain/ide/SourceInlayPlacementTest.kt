package com.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 使用真实编辑器核对同视觉行提示的垂直顺序，不启动 IDE 窗口。 */
class SourceInlayPlacementTest {
    private lateinit var fixture: CodeInsightTestFixture

    @BeforeEach
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("SourceInlayPlacementTest").fixture)
        fixture.setUp()
    }

    @AfterEach
    fun tearDown() = fixture.tearDown()

    @Test
    fun `source label stays closest to code across different offsets and hint refreshes`() = onEdt {
        fixture.configureByText("SourceInlayPlacement.txt", "    first()\n    second()\n")
        val editor = fixture.editor
        val before = hint(editor, 4, 1)
        val marker = EditorSourceInsightMarker(editor, 4, editor.document.textLength, "源码说明",
            SourceInlayNavigation(false, false, {}, {}))
        var after: Inlay<*>? = null
        var refreshed: Inlay<*>? = null
        try {
            assertTrue(marker.isInstalled)
            assertClosestToCode(editor)
            after = hint(editor, 8, 200)
            assertClosestToCode(editor)
            before.dispose()
            assertClosestToCode(editor)
            refreshed = hint(editor, 2, 0)
            assertClosestToCode(editor)
            after.dispose()
            assertClosestToCode(editor)
        } finally {
            marker.dispose()
            before.dispose()
            after?.dispose()
            refreshed?.dispose()
        }
    }

    @Test
    fun `expanded label and scrolling preserve placement and release the inlay`() = onEdt {
        val text = (1..30).joinToString("\n") { "    call$it()" }
        fixture.configureByText("SourceInlayPlacement.txt", text)
        val editor = fixture.editor
        val start = editor.document.getLineStartOffset(10)
        val other = hint(editor, start + 4, 1)
        val marker = EditorSourceInsightMarker(editor, start + 4,
            editor.document.getLineEndOffset(12), "很长的源码说明".repeat(60),
            SourceInlayNavigation(false, false, {}, {}))
        try {
            val label = editor.inlayModel.getBlockElementsForVisualLine(10, true)
                .single { it.renderer is InsightInlayRenderer }
            (label.renderer as InsightInlayRenderer).expanded = true
            label.update()
            editor.contentComponent.setSize(400, 200)
            editor.scrollingModel.disableAnimation()
            editor.scrollingModel.scrollVertically(editor.lineHeight * 8)
            assertClosestToCode(editor, 10)
            marker.dispose()
            assertTrue(editor.inlayModel.getBlockElementsForVisualLine(10, true)
                .none { it.renderer is InsightInlayRenderer })
        } finally {
            marker.dispose()
            other.dispose()
        }
    }

    private fun hint(editor: Editor, offset: Int, priority: Int): Inlay<*> = assertNotNull(
        editor.inlayModel.addBlockElement(offset, false, true, priority, object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int = 80
            override fun calcHeightInPixels(inlay: Inlay<*>): Int = 16
        }),
    )

    private fun assertClosestToCode(editor: Editor, visualLine: Int = 0) {
        val inlays = editor.inlayModel.getBlockElementsForVisualLine(visualLine, true)
        val label = inlays.single { it.renderer is InsightInlayRenderer }
        val labelBounds = assertNotNull(label.bounds)
        inlays.filter { it !== label }.forEach { other ->
            val bounds = assertNotNull(other.bounds)
            assertTrue(bounds.y + bounds.height <= labelBounds.y, "其他提示应全部位于 Lessplain 标签上方")
        }
        val codeY = editor.visualPositionToXY(com.intellij.openapi.editor.VisualPosition(visualLine, 0)).y
        assertEquals(codeY, labelBounds.y + labelBounds.height)
    }

    private fun onEdt(body: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait({ body() }, ModalityState.nonModal())
    }
}
