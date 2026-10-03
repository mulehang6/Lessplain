package com.master.understanding.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasNavigationSessionsTest {
    @Test
    fun `source stepping uses active owner pauses choices and releases registrations`() {
        val sessions = CanvasNavigationSessions()
        var panelSteps = 0
        var editorSteps = 0
        var busy = false
        val panel = sessions.register({ r, n -> r == "child" && n == "real-node" }, { busy }, { panelSteps-- }, { panelSteps++ })
        val editor = sessions.register({ r, n -> r == "child" && n == "real-node" }, { false }, { editorSteps-- }, { editorSteps++ })
        panel.activate()
        assertTrue(sessions.step("child", "real-node", backward = false))
        assertEquals(1, panelSteps)
        assertEquals(0, editorSteps)
        busy = true
        editor.activate()
        assertTrue(sessions.step("child", "real-node", backward = true))
        assertEquals(0, editorSteps, "a pending route choice must pause every matching source entry")
        panel.close()
        assertTrue(sessions.step("child", "real-node", backward = true))
        assertEquals(-1, editorSteps)
        assertFalse(sessions.step("different", "real-node", backward = false))
        editor.close()
        assertFalse(sessions.step("child", "real-node", backward = false))
    }
}
