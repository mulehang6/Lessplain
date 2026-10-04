package com.master.understanding.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.Point
import javax.swing.JPanel
import kotlin.test.assertSame

class CanvasNavigationSessionsTest {
    @Test
    fun `a busy matching owner receives the source anchor instead of swallowing the source entry`() {
        val sessions = CanvasNavigationSessions()
        val component = JPanel()
        val anchor = Point(240, 80)
        var opened = 0
        var cancelled = 0
        val busy = sessions.register(
            { _, _ -> true }, { true }, {}, {},
            sourceStep = { backward, owner, point ->
                assertTrue(backward)
                assertSame(component, owner)
                assertEquals(anchor, point)
                opened++
            }, cancel = { cancelled++ })
        val idle = sessions.register({ _, _ -> true }, { false }, {}, {})
        idle.activate()
        assertTrue(sessions.step("record", "node", true, component, anchor))
        assertEquals(1, opened)
        sessions.cancelChoices("record", "node")
        assertEquals(1, cancelled)
        busy.close()
        idle.close()
    }

    @Test
    fun `source stepping uses active owner pauses choices and releases registrations`() {
        val sessions = CanvasNavigationSessions()
        var panelSteps = 0
        var editorSteps = 0
        var busy = false
        val panel = sessions.register(
            { r, n -> r == "child" && n == "real-node" },
            { busy },
            { panelSteps-- },
            { panelSteps++ })
        val editor = sessions.register(
            { r, n -> r == "child" && n == "real-node" },
            { false },
            { editorSteps-- },
            { editorSteps++ })
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
