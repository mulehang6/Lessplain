package com.master.lessplain.ui

import org.junit.jupiter.api.Test
import java.awt.Font
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class CanvasUiFontsTest {
    @Test fun `open native note editor follows UI font and releases global listeners`() {
        val original = UIManager.getFont("Label.font")
        lateinit var editor: CanvasTextEditor
        var cleanup: CanvasTextEditor? = null
        var closedFont: Font? = null
        var defaultsBefore = 0
        var managerBefore = 0
        var resizeUpdates = 0
        val metricsChanged = CountDownLatch(1)
        try {
            SwingUtilities.invokeAndWait {
                UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, 17))
                defaultsBefore = UIManager.getDefaults().propertyChangeListeners.size
                managerBefore = UIManager.getPropertyChangeListeners().size
                editor = CanvasTextEditor("注释", CanvasTextDraft("中文"), true, {}, {}, {}, {}, onMetricsChanged = {
                    resizeUpdates++
                    if (cleanup?.input?.font?.size == 22) metricsChanged.countDown()
                })
                cleanup = editor
                assertEquals(17, editor.input.font.size)
                assertEquals(editor.input.font.family, editor.saveButton.font.family)
                assertEquals(editor.input.font.size, editor.cancelButton.font.size)
                assertFalse(editor.input.font.isBold)
                assertEquals("注释", editor.input.accessibleContext.accessibleName)
                UIManager.put("Label.font", Font(Font.SERIF, Font.PLAIN, 22))
            }
            assertTrue(metricsChanged.await(5, TimeUnit.SECONDS), "字体更新后应收到尺寸变化回调")
            SwingUtilities.invokeAndWait {
                assertEquals(22, editor.input.font.size)
                assertEquals(Font(Font.SERIF, Font.PLAIN, 22).family, editor.input.font.family)
                assertEquals(22, editor.saveButton.font.size)
                assertTrue(resizeUpdates > 0)
                editor.close()
                editor.close()
                closedFont = editor.input.font
                assertEquals(defaultsBefore, UIManager.getDefaults().propertyChangeListeners.size)
                assertEquals(managerBefore, UIManager.getPropertyChangeListeners().size)
                UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, 25))
            }
            SwingUtilities.invokeAndWait { assertSame(closedFont, editor.input.font) }
        } finally {
            SwingUtilities.invokeAndWait { cleanup?.close(); UIManager.put("Label.font", original) }
        }
    }
}
