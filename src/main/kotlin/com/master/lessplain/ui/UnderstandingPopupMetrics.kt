package com.master.lessplain.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.JLabel

/** Measures record popup rows in native pixels using the current IDE UI font. */
internal fun recentRecordPopupSize(titles: List<String>, font: Font = JBFont.regular()): Dimension {
    val metrics = JLabel().getFontMetrics(font)
    val rows = titles.size.coerceIn(1, UnderstandingUiMetrics.POPUP_VISIBLE_ROWS)
    val rowHeight = metrics.height + JBUI.scale(UnderstandingUiMetrics.ROW_VERTICAL_PADDING * 2 +
        UnderstandingUiMetrics.SMALL_GAP)
    val width = (titles.maxOfOrNull(metrics::stringWidth) ?: 0) +
        JBUI.scale((UnderstandingUiMetrics.ROW_HORIZONTAL_PADDING + UnderstandingUiMetrics.GAP) * 2)
    return Dimension(
        width.coerceIn(JBUI.scale(UnderstandingUiMetrics.POPUP_MIN_WIDTH),
            JBUI.scale(UnderstandingUiMetrics.POPUP_MAX_WIDTH)),
        rowHeight * rows + JBUI.scale(UnderstandingUiMetrics.SMALL_GAP * (rows - 1) +
            UnderstandingUiMetrics.GAP * 2),
    )
}
