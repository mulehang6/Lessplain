package com.master.lessplain.ide

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClickedNotePopupTest {
    @Test fun `same badge toggles another badge switches and stale dismissal cannot close replacement`() {
        val state = ClickedNotePopup()
        assertTrue(state.click("record", "a"))
        val old = state.generation
        assertTrue(state.click("record", "b"))
        assertFalse(state.close(old))
        assertEquals("record" to "b", state.target)
        assertFalse(state.click("record", "b"))
        assertNull(state.target)
        assertTrue(state.click("record", "b"))
        assertTrue(state.close())
        assertNull(state.target)
        assertTrue(state.click("other-record", "b"))
        assertEquals("other-record" to "b", state.target)
    }
}
