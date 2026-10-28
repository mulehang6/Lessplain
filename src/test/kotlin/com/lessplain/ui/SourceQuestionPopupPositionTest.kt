package com.lessplain.ui

import com.lessplain.ide.selectedCodePopupAnchor
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.*

class SourceQuestionPopupPositionTest {
    @Test fun `single line popup aligns with code and opens just below it`() {
        val anchor = selectedCodePopupAnchor(Point(640, 300), Point(900, 300), 24, Rectangle(100, 200, 1000, 600))
        assertEquals(Rectangle(640, 300, 1, 24), anchor)
        assertEquals(Rectangle(640, 332, 420, 180), popupNearCodeBounds(anchor, Dimension(420, 180), Rectangle(0, 0, 1500, 1000), 8))
    }

    @Test fun `selection at bottom opens above instead of covering the selected line`() {
        val anchor = selectedCodePopupAnchor(Point(770, 850), Point(1100, 850), 40, Rectangle(0, 120, 1300, 780))
        val bounds = popupNearCodeBounds(anchor, Dimension(780, 240), Rectangle(0, 0, 1360, 920), 8)
        assertEquals(602, bounds.y)
        assertTrue(bounds.y + bounds.height <= anchor.y - 8)
        assertEquals(580, bounds.x, "靠近右侧时只横向移动到屏幕内部")
    }

    @Test fun `multiline selection uses visible source rows and supports negative monitor coordinates`() {
        val anchor = selectedCodePopupAnchor(Point(220, 180), Point(500, 480), 24, Rectangle(100, 260, 900, 300))
        assertEquals(Rectangle(220, 260, 1, 244), anchor)
        val bounds = popupNearCodeBounds(Rectangle(-250, 880, 1, 24), Dimension(440, 200), Rectangle(-1920, 0, 1920, 1080), 8)
        assertEquals(Rectangle(-440, 672, 440, 200), bounds)
    }

    @Test fun `offscreen and oversized selections remain on available screen`() {
        val anchor = selectedCodePopupAnchor(Point(4000, -100), Point(4500, 3000), 32, Rectangle(200, 500, 600, 300))
        assertEquals(Rectangle(799, 500, 1, 300), anchor)
        val screen = Rectangle(0, 0, 600, 500)
        val bounds = popupNearCodeBounds(anchor, Dimension(900, 800), screen, 8)
        assertEquals(screen, bounds)
    }
}
