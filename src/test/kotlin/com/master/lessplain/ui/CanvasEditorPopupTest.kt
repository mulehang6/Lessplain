package com.master.lessplain.ui

import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.assertEquals

class CanvasEditorPopupTest {
    @Test fun `resizing keeps the current position when the larger popup still fits`() {
        val location = Point(-1480, 360)
        val screen = Rectangle(-1920, 0, 1920, 1080)

        val bounds = clampPopupBounds(location, Dimension(440, 380), screen)

        assertEquals(Rectangle(-1480, 360, 440, 380), bounds)
    }

    @Test fun `resizing clamps only the edges that leave the current monitor`() {
        val location = Point(1500, 850)
        val screen = Rectangle(0, 0, 1920, 1080)

        val bounds = clampPopupBounds(location, Dimension(440, 360), screen)

        assertEquals(Rectangle(1480, 720, 440, 360), bounds)
    }

    @Test fun `popup larger than a small screen is limited to its available bounds`() {
        val location = Point(-200, -100)
        val screen = Rectangle(-300, -200, 240, 150)

        val bounds = clampPopupBounds(location, Dimension(440, 360), screen)

        assertEquals(screen, bounds)
    }
}
