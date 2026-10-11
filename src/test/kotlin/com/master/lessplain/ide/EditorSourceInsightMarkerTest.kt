package com.master.lessplain.ide

import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class EditorSourceInsightMarkerTest {
    @Test
    fun `changed excerpt clears its marker while unchanged excerpt keeps it`() {
        lateinit var fixture: SourceMarkerTestEditor
        lateinit var marker: EditorSourceInsightMarker
        var invalidations = 0
        SwingUtilities.invokeAndWait {
            fixture = SourceMarkerTestEditor()
            marker = EditorSourceInsightMarker(fixture.editor, 0, fixture.text.length, "提示",
                SourceInlayNavigation(false, false, {}, {}, evidenceChanged = { invalidations++; marker.dispose() }))
            fixture.changeDocument()
        }
        SwingUtilities.invokeAndWait {
            assertEquals(0, invalidations)
            assertFalse(fixture.inlayDisposed)
            fixture.changeDocument(fixture.text.replace("foo", "baz"))
        }
        SwingUtilities.invokeAndWait {
            assertEquals(1, invalidations)
            assertTrue(fixture.inlayDisposed)
            assertTrue(fixture.highlighterDisposed)
            assertTrue(fixture.documentListeners.isEmpty())
            fixture.changeDocument()
        }
        SwingUtilities.invokeAndWait { assertEquals(1, invalidations) }
    }

    @Test
    fun `document changes cancel choices and disposed markers stop receiving changes`() {
        SwingUtilities.invokeAndWait {
            val fixture = SourceMarkerTestEditor()
            var cancelled = 0
            val marker = EditorSourceInsightMarker(fixture.editor, 0, fixture.text.length, "提示",
                SourceInlayNavigation(true, true, {}, {}, { cancelled++ }))
            fixture.changeDocument()
            assertEquals(1, cancelled)
            marker.dispose()
            assertEquals(2, cancelled)
            fixture.changeDocument()
            assertEquals(2, cancelled)
        }
    }

    @Test
    fun `real marker events retain block highlight and any button press dismisses it`() {
        SwingUtilities.invokeAndWait {
            val fixture = SourceMarkerTestEditor()
            val marker = EditorSourceInsightMarker(fixture.editor, 0, fixture.text.length, "提示", navigation())
            try {
                val hint = fixture.renderer.hintBounds(fixture.inlay)
                val caption = Point(hint.x + 8, hint.y + 8)
                val block = requireNotNull(sourceBlockBounds(fixture.editor, 0, fixture.text.length))
                val code = Point(block.x + 10, block.y + 15)
                move(fixture, code)
                assertFalse(fixture.renderer.hovered)
                move(fixture, caption)
                assertTrue(fixture.renderer.hovered)
                assertTrue(marker.timer.isRunning)
                move(fixture, code)
                assertTrue(fixture.renderer.hovered)
                press(fixture, code, MouseEvent.BUTTON3)
                assertFalse(fixture.renderer.hovered)
                assertFalse(marker.timer.isRunning)
                move(fixture, caption)
                assertTrue(fixture.renderer.hovered)
                press(fixture, caption, MouseEvent.BUTTON1)
                move(fixture, caption)
                assertFalse(fixture.renderer.hovered)
                move(fixture, Point(block.maxX.toInt() + 30, block.maxY.toInt() + 30))
                move(fixture, caption)
                assertTrue(fixture.renderer.hovered)
            } finally { marker.dispose() }
        }
    }

    @Test
    fun `navigation and expansion survive dismissal and disposal releases all owned resources`() {
        SwingUtilities.invokeAndWait {
            val fixture = SourceMarkerTestEditor()
            val mouseCount = fixture.component.mouseListeners.size
            val motionCount = fixture.component.mouseMotionListeners.size
            val resizeCount = fixture.component.componentListeners.size
            var previous = 0
            var next = 0
            var cancelled = 0
            val marker = EditorSourceInsightMarker(fixture.editor, 0, fixture.text.length,
                "很长的代码提示".repeat(60), SourceInlayNavigation(canPrevious = true, canNext = true,
                    previous = { previous++ }, next = { next++ }, cancelChoices = { cancelled++ }))
            try {
                assertEquals(mouseCount + 1, fixture.component.mouseListeners.size)
                assertEquals(1, fixture.documentListeners.size)
                val hint = fixture.renderer.hintBounds(fixture.inlay)
                click(fixture, Point(hint.maxX.toInt() - 33, hint.y + 10))
                click(fixture, Point(hint.maxX.toInt() - 10, hint.y + 10))
                assertEquals(1, previous)
                assertEquals(1, next)
                val secondLine = hint.y + fixture.component.getFontMetrics(TEST_FONT).height + 4
                val expandX = (hint.x until hint.maxX.toInt()).first {
                    fixture.renderer.buttonAt(fixture.inlay, Point(it, secondLine)) == SourceInlayButton.EXPAND
                }
                click(fixture, Point(expandX, secondLine))
                assertTrue(fixture.renderer.expanded)
                val block = requireNotNull(sourceBlockBounds(fixture.editor, 0, fixture.text.length))
                assertTrue(block.y > hint.y + hint.height)
                move(fixture, Point(hint.x + 8, hint.y + 8))
                move(fixture, Point(0, 0))
                move(fixture, Point(hint.x + 8, hint.y + 8))
                assertTrue(marker.timer.isRunning)
            } finally { marker.dispose() }
            marker.dispose()
            assertEquals(1, cancelled)
            assertTrue(fixture.documentListeners.isEmpty())
            assertFalse(marker.timer.isRunning)
            assertTrue(fixture.inlayDisposed)
            assertTrue(fixture.highlighterDisposed)
            assertEquals(mouseCount, fixture.component.mouseListeners.size)
            assertEquals(motionCount, fixture.component.mouseMotionListeners.size)
            assertEquals(resizeCount, fixture.component.componentListeners.size)
            move(fixture, Point(80, 40))
            assertFalse(marker.timer.isRunning)
        }
    }

    @Test
    fun `block hit area uses content coordinates independently of the visible viewport`() {
        SwingUtilities.invokeAndWait {
            val fixture = SourceMarkerTestEditor()
            val original = requireNotNull(sourceBlockBounds(fixture.editor, 0, fixture.text.length))
            fixture.visibleArea = java.awt.Rectangle(100, 200, 200, 20)
            assertEquals(original, sourceBlockBounds(fixture.editor, 0, fixture.text.length))
            assertTrue(original.height >= 40)
        }
    }

    private fun navigation() = SourceInlayNavigation(canPrevious = true, canNext = true,
        previous = {}, next = {})

    private fun move(fixture: SourceMarkerTestEditor, point: Point) = fixture.component.dispatchEvent(
        MouseEvent(fixture.component, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false),
    )

    private fun press(fixture: SourceMarkerTestEditor, point: Point, button: Int) = fixture.component.dispatchEvent(
        MouseEvent(fixture.component, MouseEvent.MOUSE_PRESSED, 0, 0, point.x, point.y, 1, false, button),
    )

    private fun click(fixture: SourceMarkerTestEditor, point: Point) {
        press(fixture, point, MouseEvent.BUTTON1)
        fixture.component.dispatchEvent(MouseEvent(fixture.component, MouseEvent.MOUSE_CLICKED,
            0, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1))
    }

    private companion object {
        val TEST_FONT = java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 12)
    }
}
