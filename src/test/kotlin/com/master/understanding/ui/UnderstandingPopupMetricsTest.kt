package com.master.understanding.ui

import com.intellij.util.ui.JBUI
import java.awt.Font
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnderstandingPopupMetricsTest {
    @Test fun `record popup measures UI fonts caps long labels and limits visible rows`() {
        val titles = List(6) { "近期记录" }
        val small = recentRecordPopupSize(titles, Font(Font.DIALOG, Font.PLAIN, 13))
        val large = recentRecordPopupSize(titles, Font(Font.DIALOG, Font.PLAIN, 32))
        assertTrue(large.height > small.height)
        assertEquals(JBUI.scale(UnderstandingUiMetrics.POPUP_MIN_WIDTH), small.width)
        assertEquals(JBUI.scale(UnderstandingUiMetrics.POPUP_MAX_WIDTH),
            recentRecordPopupSize(listOf("中文和长标题 😀 ".repeat(100))).width)
        assertEquals(recentRecordPopupSize(List(8) { "记录" }).height,
            recentRecordPopupSize(List(25) { "记录" }).height)
        assertTrue(recentRecordPopupSize(emptyList()).height > 0)
    }
}
