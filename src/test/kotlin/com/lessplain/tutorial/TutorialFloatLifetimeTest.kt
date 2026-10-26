package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.*

class TutorialFloatLifetimeTest {
    @Test fun `hover survives crossing the gap into a button but closes after leaving both`() = SwingUtilities.invokeAndWait {
        var hides = 0
        TutorialFloatLifetime { hides++ }.use { state ->
            state.shown(AnnotationFloatOrigin.HOVER)
            state.text(false)
            state.bar(true)
            state.timeout()
            assertEquals(0, hides)
            state.bar(false)
            state.text(true)
            state.timeout()
            assertEquals(0, hides)
            state.text(false)
            state.timeout()
            assertEquals(1, hides)
            assertNull(state.origin)
        }
    }

    @Test fun `selection does not expire on mouse exit while dismiss stops pending hover timeout`() = SwingUtilities.invokeAndWait {
        var hides = 0
        TutorialFloatLifetime { hides++ }.use { state ->
            state.shown(AnnotationFloatOrigin.SELECTION)
            state.text(false); state.bar(false); state.timeout()
            assertEquals(AnnotationFloatOrigin.SELECTION, state.origin)
            assertEquals(0, hides)
            state.clear()
            state.shown(AnnotationFloatOrigin.HOVER)
            state.text(false)
            state.clear(); state.timeout()
            assertEquals(0, hides)
            assertNull(state.origin)
        }
    }
}
