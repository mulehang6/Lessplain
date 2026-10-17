package com.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.command.WriteCommandAction
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

    @Test
    fun `moving unchanged source keeps the hint aligned with its new anchored line`() {
        lateinit var marker: EditorSourceInsightMarker
        onEdt {
            fixture.configureByText("MovedSource.txt", "class Sample {\n    before()\n        target()\n}\n")
            val editor = fixture.editor
            val start = editor.document.text.indexOf("target()")
            marker = EditorSourceInsightMarker(editor, start, start + "target()".length, "目标源码",
                SourceInlayNavigation(false, false, {}, {}))
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                editor.document.insertString(0, "// header\nnewPrefix()\n")
            }
        }
        // 队列中的文档监听器先完成 range 更新，再核对真实编辑器几何。
        onEdt {
            try {
                val editor = fixture.editor
                val start = editor.document.text.indexOf("target()")
                val line = editor.offsetToVisualPosition(start).line
                assertClosestToCode(editor, line)
                val inlay = editor.inlayModel.getBlockElementsForVisualLine(line, true)
                    .single { it.renderer is InsightInlayRenderer }
                val block = assertNotNull(sourceBlockBounds(editor, start, start + "target()".length))
                assertEquals(block.x, (inlay.renderer as InsightInlayRenderer).hintBounds(inlay).x)
            } finally {
                marker.dispose()
            }
        }
    }

    @Test
    fun `label strips legacy circled decoration without changing binding text`() {
        assertEquals("RedisCache", sourceInlayDisplayLabel("① RedisCache"))
        assertEquals("对象", sourceInlayDisplayLabel("  ③ 对象"))
        assertEquals("Plain", sourceInlayDisplayLabel("Plain"))
    }

    @Test
    fun `dynamic hints and folding keep the source label nearest its anchored code line`() = onEdt {
        fixture.configureByText(
            "SourceInlayPlacement.kt",
            "class Sample {\n    fun run() {\n        before()\n        target()\n        after()\n    }\n}\n",
        )
        val editor = fixture.editor
        val targetLine = 3
        val targetStart = editor.document.getLineStartOffset(targetLine)
        val targetOffset = targetStart + "        ".length
        val marker = EditorSourceInsightMarker(
            editor,
            targetOffset,
            editor.document.getLineEndOffset(targetLine),
            "目标源码",
            SourceInlayNavigation(false, false, {}, {}),
        )
        var vision: Inlay<*>? = null
        var laterHint: Inlay<*>? = null
        try {
            assertClosestToCode(editor, editor.offsetToVisualPosition(targetOffset).line)
            val addedVision = hint(editor, targetStart + 4, 20)
            vision = addedVision
            assertClosestToCode(editor, editor.offsetToVisualPosition(targetOffset).line)
            addedVision.dispose()
            vision = null
            val addedLaterHint = hint(editor, targetStart + 7, 200)
            laterHint = addedLaterHint
            assertClosestToCode(editor, editor.offsetToVisualPosition(targetOffset).line)

            editor.foldingModel.runBatchFoldingOperation {
                val fold = assertNotNull(editor.foldingModel.addFoldRegion(
                    editor.document.getLineStartOffset(0),
                    editor.document.getLineEndOffset(2),
                    "…",
                ))
                fold.isExpanded = false
            }
            assertClosestToCode(editor, editor.offsetToVisualPosition(targetOffset).line)
            addedLaterHint.dispose()
            laterHint = null
            assertClosestToCode(editor, editor.offsetToVisualPosition(targetOffset).line)
        } finally {
            marker.dispose()
            vision?.dispose()
            laterHint?.dispose()
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
