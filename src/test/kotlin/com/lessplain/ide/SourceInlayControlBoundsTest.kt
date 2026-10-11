package com.lessplain.ide

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SourceInlayControlBoundsTest {
    @Test
    fun `long source captions wrap without losing characters`() {
        val caption = "写入 app、module、signature、apiPrefix 后继续处理"
        val lines = wrapSourceInlayLabel(caption, 12) { it.length }
        assertTrue(lines.size > 2)
        assertEquals(caption, lines.joinToString(""))
        assertTrue(lines.all { it.length <= 12 })
    }
    @Test
    fun `step buttons stay beside the hint and keep separate hit areas`() {
        for (left in listOf(0, 80, 240)) {
            for (textWidth in listOf(120, 460)) {
                val bounds = sourceInlayControlBounds(left, 30, 24, textWidth)
                assertEquals(left, bounds.hint.x)
                assertTrue(bounds.previous.x >= left + textWidth)
                assertTrue(bounds.hint.contains(bounds.previous))
                assertTrue(bounds.hint.contains(bounds.next))
                assertFalse(bounds.previous.intersects(bounds.next))
                assertEquals(bounds.previous.maxX.toInt() + 3, bounds.next.x)
            }
        }
    }
}
