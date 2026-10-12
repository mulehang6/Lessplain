package com.lessplain.ide

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SourceHighlightHoverGeometryTest {
    @Test
    fun `bridge only covers the short gap and shared horizontal interval`() {
        val hint = Rectangle(20, 10, 120, 20)
        val block = Rectangle(40, 34, 180, 80)
        val bridge = requireNotNull(sourceHighlightHoverBridge(hint, block, 4))
        assertEquals(Rectangle(40, 30, 100, 4), bridge)
        assertTrue(bridge.contains(Point(40, 30)))
        assertTrue(bridge.contains(Point(139, 33)))
        assertFalse(bridge.contains(Point(39, 31)))
        assertFalse(bridge.contains(Point(140, 31)))
        assertFalse(bridge.contains(Point(60, 34)))
    }

    @Test
    fun `missing empty separated or already touching bounds have no bridge`() {
        val hint = Rectangle(20, 10, 120, 20)
        assertNull(sourceHighlightHoverBridge(null, hint, 4))
        assertNull(sourceHighlightHoverBridge(hint, null, 4))
        assertNull(sourceHighlightHoverBridge(Rectangle(), hint, 4))
        assertNull(sourceHighlightHoverBridge(hint, Rectangle(), 4))
        assertNull(sourceHighlightHoverBridge(hint, Rectangle(140, 32, 100, 80), 4))
        assertNull(sourceHighlightHoverBridge(hint, Rectangle(20, 30, 100, 80), 4))
        assertNull(sourceHighlightHoverBridge(hint, Rectangle(20, 29, 100, 80), 4))
        assertNull(sourceHighlightHoverBridge(hint, Rectangle(20, 35, 100, 80), 4))
    }

    @Test
    fun `maximum gap follows scaled pixels and recomputed bounds`() {
        val hint = Rectangle(20, 10, 120, 20)
        val block = Rectangle(40, 38, 180, 80)
        assertNull(sourceHighlightHoverBridge(hint, block, 4))
        assertEquals(Rectangle(40, 30, 100, 8), sourceHighlightHoverBridge(hint, block, 8))
        hint.translate(70, 200)
        block.translate(70, 200)
        assertEquals(Rectangle(110, 230, 100, 8), sourceHighlightHoverBridge(hint, block, 8))
        hint.height += 8
        assertNull(sourceHighlightHoverBridge(hint, block, 8))
    }
}
