package com.master.lessplain.ide

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SourceHighlightHoverStateTest {
    @Test
    fun `only hint entry starts highlight and full block keeps it alive`() {
        val state = SourceHighlightHoverState()
        assertFalse(state.move(inHint = false, inBlock = true))
        assertTrue(state.move(inHint = true, inBlock = false))
        repeat(5) { assertTrue(state.move(inHint = false, inBlock = true)) }
        assertTrue(state.move(inHint = true, inBlock = false))
        assertFalse(state.move(inHint = false, inBlock = false))
        assertFalse(state.move(inHint = false, inBlock = true))
        assertTrue(state.move(inHint = true, inBlock = false))
    }

    @Test
    fun `press in hint suppresses highlight until hint is left and entered again`() {
        val state = SourceHighlightHoverState()
        state.move(inHint = true, inBlock = false)
        assertFalse(state.press(inHint = true, inBlock = false))
        repeat(5) { assertFalse(state.move(inHint = true, inBlock = false)) }
        assertFalse(state.move(inHint = false, inBlock = true))
        assertFalse(state.move(inHint = false, inBlock = true))
        assertTrue(state.move(inHint = true, inBlock = false))
    }

    @Test
    fun `press and selection in code cannot restart reveal`() {
        val state = SourceHighlightHoverState()
        state.move(inHint = true, inBlock = false)
        state.move(inHint = false, inBlock = true)
        assertFalse(state.press(inHint = false, inBlock = true))
        assertTrue(state.move(inHint = true, inBlock = false), "代码块内点击后，直接进入提示属于新的 hover")
        state.move(inHint = false, inBlock = true)
        assertFalse(state.press(inHint = false, inBlock = true))
        assertFalse(state.drag(inHint = false, inBlock = true))
        assertFalse(state.drag(inHint = true, inBlock = false))
        assertFalse(state.drag(inHint = false, inBlock = true))
        assertFalse(state.move(inHint = false, inBlock = false))
        assertTrue(state.move(inHint = true, inBlock = false))
        assertFalse(state.press(inHint = true, inBlock = false))
        state.exit()
        assertFalse(state.active)
        assertTrue(state.move(inHint = true, inBlock = false))
    }

    @Test
    fun `unrelated presses preserve reveal and disposed state ignores queued events`() {
        val state = SourceHighlightHoverState()
        state.move(inHint = true, inBlock = false)
        assertTrue(state.press(inHint = false, inBlock = false))
        state.dispose()
        state.exit()
        assertFalse(state.move(inHint = true, inBlock = true))
        assertFalse(state.press(inHint = true, inBlock = true))
    }
}
