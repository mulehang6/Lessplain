package com.master.understanding.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasNavigationSessionsTest {
    @Test
    fun `a busy matching owner receives the source direction instead of swallowing it`() {
        val sessions = CanvasNavigationSessions()
        var opened = 0
        var cancelled = 0
        val busy = sessions.register(
            { _, _ -> true }, { true }, {}, {},
            sourceStep = { backward ->
                assertTrue(backward)
                opened++
            }, cancel = { cancelled++ })
        val idle = sessions.register({ _, _ -> true }, { false }, {}, {})
        idle.activate()
        assertTrue(sessions.step("record", "node", true))
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

    @Test
    fun `availability waits for the matching visible canvas and runs once across activation`() {
        val sessions = CanvasNavigationSessions()
        var visible = false
        var steps = 0
        val pending = sessions.whenAvailable("record", "node") {
            assertTrue(sessions.step("record", "node", backward = true))
        }
        val unrelated = sessions.register({ r, _ -> r == "other" }, { false }, {}, {})
        val owner = sessions.register(
            { r, n -> visible && r == "record" && n == "node" }, { false }, { steps++ }, {},
        )
        try {
            owner.activate()
            assertEquals(0, steps)
            visible = true
            owner.activate()
            assertEquals(1, steps)
            owner.activate()
            assertEquals(1, steps)
        } finally { pending.close(); owner.close(); unrelated.close() }
    }

    @Test
    fun `cancelled availability and synchronously registered canvases release requests`() {
        val sessions = CanvasNavigationSessions()
        var calls = 0
        val cancelled = sessions.whenAvailable("record", "node") { calls++ }
        cancelled.close()
        val owner = sessions.register({ _, _ -> true }, { false }, {}, {})
        try {
            assertEquals(0, calls)
            val ready = sessions.whenAvailable("record", "node") { calls++ }
            assertEquals(1, calls)
            ready.close()
            owner.activate()
            assertEquals(1, calls)
        } finally { owner.close() }
    }
}
