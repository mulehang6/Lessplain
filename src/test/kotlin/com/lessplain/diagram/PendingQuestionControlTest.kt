package com.lessplain.diagram

import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingQuestionControlTest {
    @Test fun `shared controls expose count tooltip keyboard action and compact bounds`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("test", 1, null, null, "理解", "", null,
                UnderstandingGraph(emptyList(), emptyList()), 0)
            val diagram = UnderstandingDiagram(record, null)
            try {
                diagram.setSize(420, 300)
                diagram.doLayout()
                val control = diagram.controls.pendingQuestions
                val button = control.button
                assertTrue(button in diagram.controls.sideControls.components)
                assertFalse(button.isEnabled)
                var copies = 0
                control.update(123, { copies++ })
                assertTrue(button.isEnabled)
                assertEquals("复制提问（123）", button.accessibleContext.accessibleName)
                assertTrue(button.toolTipText.contains("复制提问（123）"))
                diagram.controls.arrangeSide(420, 300)
                assertEquals(32, button.width)
                assertEquals(32, button.height)
                assertEquals(24, button.icon.iconWidth)
                assertEquals(24, button.icon.iconHeight)
                button.doClick()
                assertEquals(1, copies)
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try { button.paint(g) } finally { g.dispose() }
                assertTrue((0 until 32).any { y -> (0 until 32).any { x -> image.getRGB(x, y) != 0 } })
                control.update(0, null)
                button.doClick()
                assertEquals(1, copies)
                assertFalse(button.isEnabled)
                control.update(0, null, "范围不存在")
                assertTrue(button.toolTipText.contains("范围不存在"))
            } finally { diagram.dispose() }
        }
    }
}
